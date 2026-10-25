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
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.dockerjava.api.model.*;
import io.fireflysoftware.core.banking.payments.core.services.core.PaymentFeeManagerService;
import io.fireflysoftware.core.banking.payments.core.services.exchange.PaymentExchangeRateManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentFeeDTO;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.exchange.PaymentExchangeRateDTO;
import io.fireflysoftware.core.banking.payments.web.PaymentsApplication;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.fireflyframework.core.filters.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(
    classes = PaymentsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.address=127.0.0.1",
      "firefly.eda.enabled=false",
      "firefly.stepevents.enabled=false"
    })
class PaymentOrderChildParentageDatabaseTest {
  @Container
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("synthetic_legacy_child_parentage")
          .withUsername("synthetic_migrator")
          .withPassword("synthetic-only")
          .withCreateContainerCmdModifier(
              c ->
                  c.getHostConfig()
                      .withPortBindings(
                          new PortBinding(
                              Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("DB_HOST", DB::getHost);
    r.add("DB_PORT", () -> DB.getMappedPort(5432));
    r.add("DB_NAME", DB::getDatabaseName);
    r.add("DB_USERNAME", DB::getUsername);
    r.add("DB_PASSWORD", DB::getPassword);
    r.add("DB_SSL_MODE", () -> "disable");
  }

  @Autowired PaymentExchangeRateManagerService fx;
  @Autowired PaymentFeeManagerService fees;
  @LocalServerPort int port;
  final JsonMapper json = JsonMapper.builder().build();
  UUID a, b, fxA, fxA2, fxB, feeA, feeA2, feeB;

  @BeforeEach
  void seed() throws Exception {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    fxA = UUID.randomUUID();
    fxA2 = UUID.randomUUID();
    fxB = UUID.randomUUID();
    feeA = UUID.randomUUID();
    feeA2 = UUID.randomUUID();
    feeB = UUID.randomUUID();
    for (UUID id : List.of(a, b))
      sql(
          "INSERT INTO"
              + " payment_order(payment_order_id,payer_account_id,beneficiary_name,payment_type,status,amount,currency_code)"
              + " VALUES(?,?,'Synthetic legacy parent','SEPA_SCT','Initiated',100,'USD')",
          id,
          UUID.randomUUID());
    for (var row : List.of(new UUID[] {fxA, a}, new UUID[] {fxA2, a}, new UUID[] {fxB, b}))
      sql(
          "INSERT INTO"
              + " payment_exchange_rate(payment_exchange_rate_id,payment_order_id,source_currency,target_currency,rate,rate_date,rate_provider,rate_type,original_amount,converted_amount)"
              + " VALUES(?,?,'USD','EUR',0.9,'2026-10-01','Synthetic provider','SPOT',100,90)",
          row[0],
          row[1]);
    for (var row : List.of(new UUID[] {feeA, a}, new UUID[] {feeA2, a}, new UUID[] {feeB, b}))
      sql(
          "INSERT INTO"
              + " payment_fee(payment_fee_id,payment_order_id,fee_type,fee_amount,fee_currency_code)"
              + " VALUES(?,?,'TRANSACTION',1,'USD')",
          row[0],
          row[1]);
  }

  static void sql(String text, Object... values) throws Exception {
    try (var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var q = c.prepareStatement(text)) {
      for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
      q.executeUpdate();
    }
  }

  static String scalar(String text, Object... values) throws Exception {
    try (var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var q = c.prepareStatement(text)) {
      for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
      try (var rows = q.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  HttpResponse<String> request(String method, String path, Object body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  String path(UUID parent, String child) {
    return "/api/v1/payment-orders/" + parent + "/" + child;
  }

  Map<String, Object> fxBody(UUID parent) {
    return Map.of(
        "paymentOrderId",
        parent,
        "sourceCurrency",
        "USD",
        "targetCurrency",
        "EUR",
        "rate",
        0.95,
        "rateDate",
        "2026-10-01T00:00:00",
        "rateProvider",
        "Synthetic update",
        "rateType",
        "SPOT",
        "originalAmount",
        100,
        "convertedAmount",
        95);
  }

  PaymentExchangeRateDTO fxDto(UUID parent, UUID id) {
    return PaymentExchangeRateDTO.builder()
        .paymentExchangeRateId(id)
        .dateCreated(LocalDateTime.of(2026, 10, 1, 0, 0))
        .paymentOrderId(parent)
        .sourceCurrency("USD")
        .targetCurrency("EUR")
        .rate(new BigDecimal("0.95"))
        .rateDate(LocalDateTime.of(2026, 10, 1, 0, 0))
        .rateProvider("Synthetic insert")
        .rateType("SPOT")
        .originalAmount(new BigDecimal("100"))
        .convertedAmount(new BigDecimal("95"))
        .build();
  }

  @Test
  void fxListAppliesParentBeforeCountAndPagination() throws Exception {
    var response =
        request(
            "GET",
            path(a, "exchange-rates") + "?pagination.pageNumber=1&pagination.pageSize=1",
            null);
    assertThat(response.statusCode()).isEqualTo(200);
    var page = json.readTree(response.body());
    assertThat(page.path("totalElements").asLong()).isEqualTo(2);
    assertThat(page.path("content")).hasSize(1);
    assertThat(page.path("content").get(0).path("paymentOrderId").asText()).isEqualTo(a.toString());
  }

  @Test
  void feeListAppliesParentBeforeCountAndPagination() throws Exception {
    var response = request("GET", path(a, "fees") + "?pagination.pageSize=1", null);
    assertThat(response.statusCode()).isEqualTo(200);
    var page = json.readTree(response.body());
    assertThat(page.path("totalElements").asLong()).isEqualTo(2);
    assertThat(page.path("content")).hasSize(1);
    assertThat(page.path("content").get(0).path("paymentOrderId").asText()).isEqualTo(a.toString());
  }

  @Test
  void contradictoryHttpParentFiltersCannotOverrideRoute() throws Exception {
    for (String child : List.of("exchange-rates", "fees")) {
      var response = request("GET", path(a, child) + "?filters.paymentOrderId=" + b, null);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var page = json.readTree(response.body());
      assertThat(page.path("totalElements").asLong()).isZero();
      assertThat(page.path("content")).isEmpty();
    }
  }

  @Test
  void httpExactCriteriaBindToTypedDtosWithoutWideningParent() throws Exception {
    for (String child : List.of("exchange-rates", "fees")) {
      String field = child.equals("fees") ? "feeType" : "sourceCurrency";
      String matching = child.equals("fees") ? "TRANSACTION" : "USD";
      var response =
          request(
              "GET",
              path(a, child) + "?filters." + field + "=" + matching + "&pagination.pageSize=1",
              null);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var page = json.readTree(response.body());
      assertThat(page.path("totalElements").asLong()).isEqualTo(2);
      assertThat(page.path("content")).hasSize(1);
      assertThat(page.path("content").get(0).path("paymentOrderId").asText())
          .isEqualTo(a.toString());
      var missing = request("GET", path(a, child) + "?filters." + field + "=ZZZ", null);
      assertThat(missing.statusCode()).as(missing.body()).isEqualTo(200);
      assertThat(json.readTree(missing.body()).path("totalElements").asLong()).isZero();
    }
  }

  @Test
  void contradictoryExactFiltersCannotReplaceRouteParent() {
    var f = new FilterRequest<PaymentExchangeRateDTO>();
    f.setFilters(PaymentExchangeRateDTO.builder().paymentOrderId(b).build());
    var g = new FilterRequest<PaymentFeeDTO>();
    g.setFilters(PaymentFeeDTO.builder().paymentOrderId(b).build());
    assertAll(
        () ->
            assertThat(fx.getExchangeRatesByPaymentOrderId(a, f).block().getTotalElements())
                .isZero(),
        () -> assertThat(fees.getAllPaymentFees(a, g).block().getTotalElements()).isZero());
  }

  @Test
  void fxNestedGetRejectsForeignParent() throws Exception {
    var response = request("GET", path(a, "exchange-rates/" + fxB), null);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).doesNotContain("Synthetic provider", b.toString());
  }

  @Test
  void fxNestedUpdateRejectsForeignParentWithoutReparenting() throws Exception {
    var response = request("PUT", path(a, "exchange-rates/" + fxB), fxBody(a));
    assertAll(
        () -> assertThat(response.statusCode()).isEqualTo(404),
        () ->
            assertThat(
                    scalar(
                        "SELECT payment_order_id FROM payment_exchange_rate WHERE"
                            + " payment_exchange_rate_id=?",
                        fxB))
                .isEqualTo(b.toString()),
        () ->
            assertThat(
                    scalar(
                        "SELECT rate_provider FROM payment_exchange_rate WHERE"
                            + " payment_exchange_rate_id=?",
                        fxB))
                .isEqualTo("Synthetic provider"));
  }

  @Test
  void fxNestedDeleteRejectsForeignParentWithoutDeleting() throws Exception {
    var response = request("DELETE", path(a, "exchange-rates/" + fxB), null);
    assertAll(
        () -> assertThat(response.statusCode()).isEqualTo(404),
        () ->
            assertThat(
                    scalar(
                        "SELECT payment_order_id FROM payment_exchange_rate WHERE"
                            + " payment_exchange_rate_id=?",
                        fxB))
                .isEqualTo(b.toString()));
  }

  @Test
  void validFxUpdateIgnoresConflictingBodyParentAndPreservesCreation() throws Exception {
    String created =
        scalar(
            "SELECT date_created FROM payment_exchange_rate WHERE payment_exchange_rate_id=?", fxA);
    var response = request("PUT", path(a, "exchange-rates/" + fxA), fxBody(b));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(json.readTree(response.body()).path("paymentOrderId").asText())
        .isEqualTo(a.toString());
    assertThat(
            scalar(
                "SELECT date_created FROM payment_exchange_rate WHERE payment_exchange_rate_id=?",
                fxA))
        .isEqualTo(created);
  }

  @Test
  void internalFxCreateIdCannotOverwriteForeignRow() throws Exception {
    var inserted = fx.createExchangeRate(a, fxDto(b, fxB)).block();
    assertAll(
        () ->
            assertThat(
                    scalar(
                        "SELECT payment_order_id FROM payment_exchange_rate WHERE"
                            + " payment_exchange_rate_id=?",
                        fxB))
                .isEqualTo(b.toString()),
        () -> assertThat(inserted.getPaymentExchangeRateId()).isNotEqualTo(fxB),
        () -> assertThat(inserted.getPaymentOrderId()).isEqualTo(a));
  }

  @Test
  void internalFeeCreateIdCannotOverwriteForeignRow() throws Exception {
    var input =
        PaymentFeeDTO.builder()
            .paymentFeeId(feeB)
            .dateCreated(LocalDateTime.of(2026, 10, 1, 0, 0))
            .paymentOrderId(b)
            .feeType("TRANSACTION")
            .feeAmount(BigDecimal.TEN)
            .feeCurrencyCode("USD")
            .build();
    var inserted = fees.createPaymentFee(a, input).block();
    assertAll(
        () ->
            assertThat(
                    scalar("SELECT payment_order_id FROM payment_fee WHERE payment_fee_id=?", feeB))
                .isEqualTo(b.toString()),
        () -> assertThat(inserted.getPaymentFeeId()).isNotEqualTo(feeB),
        () -> assertThat(inserted.getPaymentOrderId()).isEqualTo(a));
  }

  @Test
  void typedFeeCreateAndUpdateUseRouteParentAndPreserveCreation() throws Exception {
    var body =
        Map.of(
            "paymentOrderId",
            b,
            "feeType",
            "Explicit legacy label",
            "feeTypeEnum",
            "TRANSACTION_FEE",
            "feeAmount",
            2.25,
            "feeCurrencyCode",
            "USD",
            "feeWaived",
            false);
    var created = request("POST", path(a, "fees"), body);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    var row = json.readTree(created.body());
    UUID id = UUID.fromString(row.path("paymentFeeId").asText());
    assertThat(row.path("paymentOrderId").asText()).isEqualTo(a.toString());
    assertThat(row.path("feeTypeEnum").asText()).isEqualTo("TRANSACTION_FEE");
    String timestamp = scalar("SELECT date_created FROM payment_fee WHERE payment_fee_id=?", id);
    var updated = request("PUT", path(a, "fees/" + id), body);
    assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
    assertThat(json.readTree(updated.body()).path("paymentOrderId").asText())
        .isEqualTo(a.toString());
    assertThat(scalar("SELECT date_created FROM payment_fee WHERE payment_fee_id=?", id))
        .isEqualTo(timestamp);
    assertThat(scalar("SELECT fee_type FROM payment_fee WHERE payment_fee_id=?", id))
        .isEqualTo("Explicit legacy label");
  }

  @Test
  void missingScopedFxDeleteReturns404() throws Exception {
    assertThat(request("DELETE", path(a, "exchange-rates/" + UUID.randomUUID()), null).statusCode())
        .isEqualTo(404);
  }

  static class FxArray {
    @org.fireflyframework.utils.annotations.FilterableId UUID paymentOrderId;
    String[] sourceCurrency;

    FxArray(UUID parent, String[] values) {
      paymentOrderId = parent;
      sourceCurrency = values;
    }
  }

  static class FeeArray {
    @org.fireflyframework.utils.annotations.FilterableId UUID paymentOrderId;
    String[] feeCurrencyCode;

    FeeArray(UUID parent, String[] values) {
      paymentOrderId = parent;
      feeCurrencyCode = values;
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  void supportedArrayDisjunctionsAndEmptyNullFiltersCannotWidenParentScope() {
    for (String[] values : Arrays.asList(new String[] {"USD", "EUR"}, new String[] {}, null)) {
      FilterRequest<PaymentExchangeRateDTO> f = new FilterRequest<>();
      ((FilterRequest) f).setFilters(new FxArray(null, values));
      FilterRequest<PaymentFeeDTO> g = new FilterRequest<>();
      ((FilterRequest) g).setFilters(new FeeArray(null, values));
      assertAll(
          () ->
              assertThat(fx.getExchangeRatesByPaymentOrderId(a, f).block().getTotalElements())
                  .isEqualTo(2),
          () -> assertThat(fees.getAllPaymentFees(a, g).block().getTotalElements()).isEqualTo(2));
    }
    FilterRequest<PaymentExchangeRateDTO> f = new FilterRequest<>();
    ((FilterRequest) f).setFilters(new FxArray(b, new String[] {"USD", "EUR"}));
    FilterRequest<PaymentFeeDTO> g = new FilterRequest<>();
    ((FilterRequest) g).setFilters(new FeeArray(b, new String[] {"USD", "EUR"}));
    assertAll(
        () ->
            assertThat(fx.getExchangeRatesByPaymentOrderId(a, f).block().getTotalElements())
                .isZero(),
        () -> assertThat(fees.getAllPaymentFees(a, g).block().getTotalElements()).isZero());
  }

  @Test
  void nullParentMarkersAndEmptyFiltersRetainNativeMandatoryPredicate() {
    var f = new FilterRequest<PaymentExchangeRateDTO>();
    var g = new FilterRequest<PaymentFeeDTO>();
    assertAll(
        () ->
            assertThat(fx.getExchangeRatesByPaymentOrderId(a, f).block().getTotalElements())
                .isEqualTo(2),
        () -> assertThat(fees.getAllPaymentFees(a, g).block().getTotalElements()).isEqualTo(2));
    // Keep marker-only fixtures distinct from the HTTP-bound filters exercised above.
    var fd = PaymentExchangeRateDTO.builder().paymentExchangeRateId(UUID.randomUUID()).build();
    var gd = PaymentFeeDTO.builder().paymentFeeId(UUID.randomUUID()).build();
    f.setFilters(fd);
    g.setFilters(gd);
    assertAll(
        () ->
            assertThat(fx.getExchangeRatesByPaymentOrderId(a, f).block().getTotalElements())
                .isEqualTo(2),
        () -> assertThat(fees.getAllPaymentFees(a, g).block().getTotalElements()).isEqualTo(2));
    FilterRequest.setNullFilter(fd, "paymentOrderId");
    FilterRequest.setNullFilter(gd, "paymentOrderId");
    assertAll(
        () ->
            assertThat(fx.getExchangeRatesByPaymentOrderId(a, f).block().getTotalElements())
                .isZero(),
        () -> assertThat(fees.getAllPaymentFees(a, g).block().getTotalElements()).isZero());
  }

  @Test
  void updateRechecksScopedParentAfterConcurrentNativeMovement() throws Exception {
    race("PUT");
  }

  @Test
  void deleteRechecksScopedParentAfterConcurrentNativeMovement() throws Exception {
    race("DELETE");
  }

  @Test
  void feeUpdateRechecksParentAfterConcurrentNativeMovement() throws Exception {
    feeRace("PUT");
  }

  @Test
  void feeDeleteRechecksParentAfterConcurrentNativeMovement() throws Exception {
    feeRace("DELETE");
  }

  @Test
  void missingAndWrongParentFeeDeletesReturn404WithoutMutation() throws Exception {
    assertThat(request("DELETE", path(a, "fees/" + feeB), null).statusCode()).isEqualTo(404);
    assertThat(scalar("SELECT payment_order_id FROM payment_fee WHERE payment_fee_id=?", feeB))
        .isEqualTo(b.toString());
    assertThat(request("DELETE", path(a, "fees/" + UUID.randomUUID()), null).statusCode())
        .isEqualTo(404);
  }

  @Test
  void actualOpenApiPublishesTypedChildRoutes() throws Exception {
    var response = request("GET", "/v3/api-docs", null);
    assertThat(response.statusCode())
        .as("actual producer OpenAPI must be available")
        .isEqualTo(200);
    var paths = json.readTree(response.body()).path("paths");
    assertThat(paths.path("/api/v1/payment-orders/{paymentOrderId}/fees").has("get")).isTrue();
    assertThat(
            paths
                .path(
                    "/api/v1/payment-orders/{paymentOrderId}/exchange-rates/{paymentExchangeRateId}")
                .has("delete"))
        .isTrue();
  }

  void feeRace(String verb) throws Exception {
    String created = scalar("SELECT date_created FROM payment_fee WHERE payment_fee_id=?", feeA);
    try (var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())) {
      c.setAutoCommit(false);
      try (var move =
          c.prepareStatement("UPDATE payment_fee SET payment_order_id=? WHERE payment_fee_id=?")) {
        move.setObject(1, b);
        move.setObject(2, feeA);
        move.executeUpdate();
      }
      var body =
          Map.of(
              "paymentOrderId",
              a,
              "feeType",
              "Synthetic race mutation",
              "feeTypeEnum",
              "TRANSACTION_FEE",
              "feeAmount",
              8.88,
              "feeCurrencyCode",
              "USD",
              "feeWaived",
              false);
      var future =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return request(verb, path(a, "fees/" + feeA), verb.equals("PUT") ? body : null);
                } catch (Exception error) {
                  throw new RuntimeException(error);
                }
              });
      long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
      boolean waiting = false;
      while (System.nanoTime() < end) {
        waiting =
            Long.parseLong(
                    scalar(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND"
                            + " wait_event_type='Lock' AND query LIKE '%payment_fee%'"))
                > 0;
        if (waiting) break;
        Thread.sleep(20);
      }
      assertThat(waiting)
          .as("actual fee HTTP mutation waits before native parent move commit")
          .isTrue();
      c.commit();
      var response = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertAll(
          () -> assertThat(response.statusCode()).isEqualTo(404),
          () ->
              assertThat(
                      scalar(
                          "SELECT payment_order_id FROM payment_fee WHERE payment_fee_id=?", feeA))
                  .isEqualTo(b.toString()),
          () ->
              assertThat(scalar("SELECT fee_type FROM payment_fee WHERE payment_fee_id=?", feeA))
                  .isEqualTo("TRANSACTION"),
          () ->
              assertThat(scalar("SELECT fee_amount FROM payment_fee WHERE payment_fee_id=?", feeA))
                  .isEqualTo("1.00"),
          () ->
              assertThat(
                      scalar("SELECT date_created FROM payment_fee WHERE payment_fee_id=?", feeA))
                  .isEqualTo(created));
    }
  }

  void race(String verb) throws Exception {
    try (var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())) {
      c.setAutoCommit(false);
      try (var q =
          c.prepareStatement(
              "SELECT payment_exchange_rate_id FROM payment_exchange_rate WHERE"
                  + " payment_exchange_rate_id=? FOR UPDATE")) {
        q.setObject(1, fxA);
        q.executeQuery().close();
      }
      var future =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return request(
                      verb,
                      path(a, "exchange-rates/" + fxA),
                      verb.equals("PUT") ? fxBody(a) : null);
                } catch (Exception error) {
                  throw new RuntimeException(error);
                }
              });
      long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
      boolean waiting = false;
      while (System.nanoTime() < end) {
        waiting =
            Long.parseLong(
                    scalar(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND"
                            + " wait_event_type='Lock' AND query LIKE '%payment_exchange_rate%'"))
                > 0;
        if (waiting) break;
        Thread.sleep(20);
      }
      assertThat(waiting).as("actual HTTP mutation waits on the held native child row").isTrue();
      try (var q =
          c.prepareStatement(
              "UPDATE payment_exchange_rate SET payment_order_id=? WHERE"
                  + " payment_exchange_rate_id=?")) {
        q.setObject(1, b);
        q.setObject(2, fxA);
        q.executeUpdate();
      }
      c.commit();
      var response = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertAll(
          () -> assertThat(response.statusCode()).isEqualTo(404),
          () ->
              assertThat(
                      scalar(
                          "SELECT payment_order_id FROM payment_exchange_rate WHERE"
                              + " payment_exchange_rate_id=?",
                          fxA))
                  .isEqualTo(b.toString()));
    }
  }
}
