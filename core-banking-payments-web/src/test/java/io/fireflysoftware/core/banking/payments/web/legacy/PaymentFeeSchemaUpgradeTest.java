/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.core.banking.payments.web.legacy;

import static org.assertj.core.api.Assertions.*;

import com.github.dockerjava.api.model.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PaymentFeeSchemaUpgradeTest {
  @Container
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("synthetic_fee_upgrade")
          .withUsername("synthetic_migrator")
          .withPassword("synthetic-only")
          .withCreateContainerCmdModifier(
              c ->
                  c.getHostConfig()
                      .withPortBindings(
                          new PortBinding(
                              Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));

  @Test
  void existingLegacyFeesRemainExactAndNewTypedEnumIsNullable() throws Exception {
    Flyway.configure()
        .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
        .locations("classpath:db/migration")
        .target("12")
        .load()
        .migrate();
    UUID parent = UUID.randomUUID(), id = UUID.randomUUID();
    try (var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var q = c.createStatement()) {
      q.executeUpdate(
          "INSERT INTO"
              + " payment_order(payment_order_id,payer_account_id,beneficiary_name,payment_type,status,amount,currency_code)"
              + " VALUES('"
              + parent
              + "','"
              + UUID.randomUUID()
              + "','Synthetic upgrade','SEPA_SCT','Initiated',100,'USD')");
      q.executeUpdate(
          "INSERT INTO"
              + " payment_fee(payment_fee_id,payment_order_id,fee_type,fee_amount,fee_currency_code)"
              + " VALUES('"
              + id
              + "','"
              + parent
              + "','LEGACY_UNCLASSIFIED',7,'USD')");
    }
    Flyway.configure()
        .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var q = c.createStatement()) {
      try (var rows =
          q.executeQuery(
              "SELECT udt_name,is_nullable FROM information_schema.columns WHERE"
                  + " table_name='payment_fee' AND column_name='fee_type_enum'")) {
        assertThat(rows.next()).as("missing native mapped fee_type_enum column").isTrue();
        assertThat(rows.getString(1)).isEqualTo("fee_type_enum");
        assertThat(rows.getString(2)).isEqualTo("YES");
      }
      try (var rows =
          q.executeQuery(
              "SELECT fee_type,fee_type_enum,fee_amount FROM payment_fee WHERE payment_fee_id='"
                  + id
                  + "'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("LEGACY_UNCLASSIFIED");
        assertThat(rows.getString(2)).isNull();
        assertThat(rows.getBigDecimal(3)).isEqualByComparingTo("7.00");
      }
      q.executeUpdate(
          "INSERT INTO"
              + " payment_fee(payment_order_id,fee_type,fee_type_enum,fee_amount,fee_currency_code)"
              + " VALUES('"
              + parent
              + "','Distinct legacy label','TRANSACTION_FEE',2,'USD')");
      try (var rows =
          q.executeQuery(
              "SELECT fee_type,fee_type_enum FROM payment_fee WHERE fee_type_enum IS NOT NULL")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("Distinct legacy label");
        assertThat(rows.getString(2)).isEqualTo("TRANSACTION_FEE");
        assertThat(rows.next()).isFalse();
      }
    }
  }
}
