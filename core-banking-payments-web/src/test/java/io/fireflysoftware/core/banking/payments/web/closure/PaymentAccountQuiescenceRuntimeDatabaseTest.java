/*
 * Copyright 2026 Firefly Software Foundation
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

package io.fireflysoftware.core.banking.payments.web.closure;

import io.fireflysoftware.core.banking.payments.web.managed.OwnedPaymentPersistence;

import io.fireflysoftware.core.banking.payments.core.services.closure.impl.PaymentAccountQuiescenceServiceImpl;

import static org.assertj.core.api.Assertions.*;

import com.github.dockerjava.api.model.*;

import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort;
import io.fireflysoftware.core.banking.payments.core.services.closure.*;
import io.fireflysoftware.core.banking.payments.core.services.closure.impl.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.closure.PaymentAccountQuiescenceContracts.*;
import io.r2dbc.spi.ConnectionFactories;

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

@Testcontainers
class PaymentAccountQuiescenceRuntimeDatabaseTest {
    @Container
    static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("closure_native")
                    .withUsername("closure_migrator")
                    .withPassword("synthetic-owned-migrator")
                    .withCreateContainerCmdModifier(
                            c ->
                                    c.getHostConfig()
                                            .withPortBindings(
                                                    new PortBinding(
                                                            Ports.Binding.bindIpAndPort(
                                                                    "127.0.0.1", 0),
                                                            ExposedPort.tcp(5432))));

    static final UUID ACCOUNT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID ENROLLMENT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID CONTRACT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final String SELECTION =
            "{\"tenantId\":\"owned\",\"accountId\":\""
                    + ACCOUNT
                    + "\",\"enrollmentId\":\""
                    + ENROLLMENT
                    + "\",\"contractId\":\""
                    + CONTRACT
                    + "\",\"currency\":\"USD\",\"currencyScale\":2,\"currencyPolicy\":\"USD-2026\"}";

    @BeforeAll
    static void schema() throws Exception {
        try (var c = admin();
                var s = c.createStatement()) {
            s.execute(
                    "CREATE ROLE closure_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD"
                            + " 'synthetic-owned-runtime'");
            s.execute(
                    "REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO"
                            + " closure_runtime");
            s.execute(
                    "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON"
                            + " TABLES TO closure_runtime");
            for (String migration :
                    List.of(
                            "V12__Managed_internal_payments.sql",
                            "V14__Create_account_payment_quiescence.sql",
                            "V15__Managed_payment_repository_functions.sql")) {
                try (var stream =
                        PaymentAccountQuiescenceRuntimeDatabaseTest.class.getResourceAsStream(
                                "/db/migration/" + migration)) {
                    if (stream != null)
                        s.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
    }

    static Connection admin() throws SQLException {
        return DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }

    static Connection runtime() throws SQLException {
        return DriverManager.getConnection(
                DATABASE.getJdbcUrl(), "closure_runtime", "synthetic-owned-runtime");
    }

    static void context(Connection c, UUID account, String purpose) throws SQLException {
        c.setAutoCommit(false);
        try (var q =
                c.prepareStatement(
                        "SELECT"
                            + " set_config('firefly.payment_closure.tenant','owned',true),set_config('firefly.payment_closure.account',?,true),"
                            + "set_config('firefly.payment_closure.purpose',?,true),set_config('firefly.payment_closure.xid',pg_current_xact_id()::text,true),set_config('firefly.payment_closure.until',(floor(extract(epoch"
                            + " FROM clock_timestamp())*1000)::bigint+30000)::text,true),"
                            + "set_config('firefly.payment_closure.actor_issuer','https://owned.invalid',true),set_config('firefly.payment_closure.actor_subject','native-test',true)")) {
            q.setString(1, account.toString());
            q.setString(2, purpose);
            q.execute();
        }
    }

    static String quiesce(Connection c, UUID account, UUID action, UUID closure, long expected)
            throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT public.managed_payment_account_quiesce('owned',?,?,?,?,?,CAST(? AS"
                                + " jsonb),7,?)::text")) {
            q.setObject(1, account);
            q.setObject(2, action);
            q.setObject(3, closure);
            q.setObject(4, ENROLLMENT);
            q.setLong(5, expected);
            q.setString(6, SELECTION.replace(ACCOUNT.toString(), account.toString()));
            q.setString(7, "a".repeat(64));
            try (var r = q.executeQuery()) {
                assertThat(r.next()).isTrue();
                return r.getString(1);
            }
        }
    }

    static void releaseFence(Connection c, UUID account, UUID closure, long expected)
            throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT public.managed_payment_account_release('owned',?,?,?,?,?)")) {
            q.setObject(1, account);
            q.setObject(2, UUID.randomUUID());
            q.setObject(3, closure);
            q.setLong(4, expected);
            q.setString(5, "c".repeat(64));
            q.execute();
        }
    }

    static UUID appendFenceEvidence(Connection c, UUID account, UUID closure, long version)
            throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        UUID fence = UUID.randomUUID(), action = UUID.randomUUID();
        var selection = json.readTree(SELECTION.replace(ACCOUNT.toString(), account.toString()));
        var request =
                json.createObjectNode()
                        .put("actionId", action.toString())
                        .put("closureId", closure.toString())
                        .put("enrollmentId", ENROLLMENT.toString())
                        .put("expectedFenceVersion", version - 1);
        var receipt =
                json.createObjectNode()
                        .put("fenceId", fence.toString())
                        .put("actionId", action.toString())
                        .put("closureId", closure.toString())
                        .put("fenceVersion", version)
                        .put("observedSourceVersion", 0)
                        .put("observedLedgerVersion", 7)
                        .put("acceptedAt", java.time.Instant.now().toString());
        receipt.set("selection", selection);
        try (var q =
                c.prepareStatement(
                        "INSERT INTO managed_payment_account_fence VALUES('owned',?,?,?,CAST(? AS"
                            + " jsonb),CAST(? AS jsonb),7,'FENCED',?,0,?,CAST(? AS jsonb))")) {
            q.setObject(1, account);
            q.setObject(2, fence);
            q.setObject(3, closure);
            q.setString(4, selection.toString());
            q.setString(5, request.toString());
            q.setLong(6, version);
            q.setObject(7, action);
            q.setString(8, receipt.toString());
            q.executeUpdate();
        }
        appendJournal(
                c,
                account,
                fence,
                action,
                "QUIESCE",
                version,
                selection.toString(),
                request.toString(),
                receipt.toString());
        return fence;
    }

    static void appendJournal(
            Connection c,
            UUID account,
            UUID fence,
            UUID action,
            String kind,
            long version,
            String selection,
            String request,
            String receipt)
            throws SQLException {
        try (var q =
                c.prepareStatement(
                        "INSERT INTO managed_payment_account_action VALUES('owned',?,?,?,?"
                            + " ,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS"
                            + " jsonb),'https://owned.invalid','native-test')")) {
            q.setObject(1, account);
            q.setObject(2, action);
            q.setObject(3, fence);
            q.setString(4, kind);
            q.setString(5, "d".repeat(64));
            q.setString(6, selection);
            q.setString(7, request);
            q.setString(8, receipt);
            q.executeUpdate();
        }
        try (var q =
                c.prepareStatement(
                        "INSERT INTO managed_payment_account_history VALUES('owned',?,?,?,?,CAST(?"
                            + " AS jsonb))")) {
            q.setObject(1, account);
            q.setObject(2, fence);
            q.setLong(3, version);
            q.setObject(4, action);
            q.setString(5, receipt);
            q.executeUpdate();
        }
        try (var q =
                c.prepareStatement(
                        "INSERT INTO managed_payment_account_outbox"
                            + " VALUES('owned',?,?,?,?,gen_random_uuid(),CAST(? AS jsonb))")) {
            q.setObject(1, account);
            q.setObject(2, fence);
            q.setLong(3, version);
            q.setObject(4, action);
            q.setString(5, receipt);
            q.executeUpdate();
        }
    }

    @Test
    void gateCommandScopeCannotCommitWithoutMatchingRetainedFence() throws Exception {
        for (String purpose : List.of("quiesce", "release", "read")) {
            UUID account = UUID.randomUUID(), closure = UUID.randomUUID();
            try (var c = runtime()) {
                context(c, account, "quiesce");
                quiesce(c, account, UUID.randomUUID(), closure, 0);
                c.commit();
                context(c, account, purpose);
                assertThatThrownBy(
                                () -> {
                                    try (var q =
                                            c.prepareStatement(
                                                    "UPDATE managed_payment_account_gate SET"
                                                        + " active_fence_id=NULL,fence_version=fence_version+1"
                                                        + " WHERE tenant_id='owned' AND"
                                                        + " account_id=?")) {
                                        q.setObject(1, account);
                                        assertThat(q.executeUpdate()).isEqualTo(1);
                                    }
                                    c.commit();
                                })
                        .as(purpose)
                        .isInstanceOf(SQLException.class);
                c.rollback();
                context(c, account, "read");
                try (var q =
                        c.prepareStatement(
                                "SELECT g.fence_version,g.active_fence_id,f.state,f.version FROM"
                                    + " managed_payment_account_gate g JOIN"
                                    + " managed_payment_account_fence f ON f.tenant_id=g.tenant_id"
                                    + " AND f.account_id=g.account_id WHERE g.account_id=?")) {
                    q.setObject(1, account);
                    try (var rows = q.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(1);
                        assertThat(rows.getObject(2)).isNotNull();
                        assertThat(rows.getString(3)).isEqualTo("FENCED");
                        assertThat(rows.getLong(4)).isEqualTo(1);
                    }
                }
                c.commit();
            }
        }
        UUID account = UUID.randomUUID();
        try (var c = runtime()) {
            context(c, account, "quiesce");
            try (var q =
                    c.prepareStatement("SELECT public.managed_payment_account_gate('owned',?)")) {
                q.setObject(1, account);
                q.execute();
            }
            c.commit();
            context(c, account, "quiesce");
            assertThatThrownBy(
                            () -> {
                                try (var q =
                                        c.prepareStatement(
                                                "UPDATE managed_payment_account_gate SET"
                                                    + " fence_version=1 WHERE account_id=?")) {
                                    q.setObject(1, account);
                                    q.executeUpdate();
                                }
                                c.commit();
                            })
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    @Test
    void orphanFenceOrReleaseCannotCommitEvenWithCompleteJournal() throws Exception {
        UUID account = UUID.randomUUID();
        try (var c = runtime()) {
            context(c, account, "quiesce");
            try (var q =
                    c.prepareStatement("SELECT public.managed_payment_account_gate('owned',?)")) {
                q.setObject(1, account);
                q.execute();
            }
            assertThatThrownBy(
                            () -> {
                                appendFenceEvidence(c, account, UUID.randomUUID(), 1);
                                c.commit();
                            })
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
        UUID closure = UUID.randomUUID();
        try (var c = runtime()) {
            context(c, account, "quiesce");
            var accepted =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(quiesce(c, account, UUID.randomUUID(), closure, 0));
            c.commit();
            UUID fence = UUID.fromString(accepted.path("fenceId").asText()),
                    action = UUID.randomUUID();
            context(c, account, "release");
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var request =
                    json.createObjectNode()
                            .put("actionId", action.toString())
                            .put("closureId", closure.toString())
                            .put("expectedFenceVersion", 1);
            var receipt =
                    json.createObjectNode()
                            .put("fenceId", fence.toString())
                            .put("actionId", action.toString())
                            .put("closureId", closure.toString())
                            .put("fenceVersion", 2)
                            .put("releasedAt", java.time.Instant.now().toString());
            receipt.set("selection", accepted.path("selection"));
            assertThatThrownBy(
                            () -> {
                                appendJournal(
                                        c,
                                        account,
                                        fence,
                                        action,
                                        "RELEASE",
                                        2,
                                        accepted.path("selection").toString(),
                                        request.toString(),
                                        receipt.toString());
                                try (var q =
                                        c.prepareStatement(
                                                "UPDATE managed_payment_account_fence SET"
                                                    + " state='RELEASED',version=2,last_action_id=?"
                                                    + " WHERE account_id=?")) {
                                    q.setObject(1, action);
                                    q.setObject(2, account);
                                    q.executeUpdate();
                                }
                                c.commit();
                            })
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    @Test
    void eachJournalMemberMustRetainExactCommandEvidence() throws Exception {
        for (String table : List.of("action", "history", "outbox"))
            for (String mode : List.of("omit", "mutate", "null")) {
                String column = table.equals("action") ? "receipt" : "payload";
                try (var c = admin();
                        var st = c.createStatement()) {
                    st.execute(
                            "CREATE FUNCTION owned_closure_journal_fault() RETURNS trigger LANGUAGE"
                                + " plpgsql AS $$ BEGIN "
                                    + (mode.equals("omit")
                                            ? "RETURN NULL;"
                                            : "NEW."
                                                    + column
                                                    + "="
                                                    + (mode.equals("null")
                                                            ? "'null'::jsonb"
                                                            : "jsonb_set(NEW."
                                                                    + column
                                                                    + ",'{fenceVersion}','999'::jsonb)")
                                                    + "; RETURN NEW;")
                                    + " END $$");
                    st.execute(
                            "CREATE TRIGGER owned_closure_journal_fault BEFORE INSERT ON"
                                + " managed_payment_account_"
                                    + table
                                    + " FOR EACH ROW EXECUTE FUNCTION"
                                    + " owned_closure_journal_fault()");
                }
                UUID account = UUID.randomUUID();
                try {
                    try (var c = runtime()) {
                        context(c, account, "quiesce");
                        assertThatThrownBy(
                                        () -> {
                                            quiesce(
                                                    c,
                                                    account,
                                                    UUID.randomUUID(),
                                                    UUID.randomUUID(),
                                                    0);
                                            c.commit();
                                        })
                                .as(table + "/" + mode)
                                .isInstanceOf(SQLException.class);
                        c.rollback();
                        context(c, account, "read");
                        try (var q =
                                c.prepareStatement(
                                        "SELECT (SELECT count(*) FROM managed_payment_account_gate"
                                            + " WHERE account_id=?)+(SELECT count(*) FROM"
                                            + " managed_payment_account_fence WHERE"
                                            + " account_id=?)+(SELECT count(*) FROM"
                                            + " managed_payment_account_action WHERE"
                                            + " account_id=?)+(SELECT count(*) FROM"
                                            + " managed_payment_account_history WHERE"
                                            + " account_id=?)+(SELECT count(*) FROM"
                                            + " managed_payment_account_outbox WHERE"
                                            + " account_id=?)")) {
                            for (int n = 1; n <= 5; n++) q.setObject(n, account);
                            try (var rows = q.executeQuery()) {
                                rows.next();
                                assertThat(rows.getLong(1)).isZero();
                            }
                        }
                        c.commit();
                    }
                } finally {
                    try (var c = admin();
                            var st = c.createStatement()) {
                        st.execute(
                                "DROP TRIGGER owned_closure_journal_fault ON"
                                    + " managed_payment_account_"
                                        + table);
                        st.execute("DROP FUNCTION owned_closure_journal_fault()");
                    }
                }
            }
    }

    @Test
    void releaseRefenceOrderingAndSourceRevisionUpdatesRemainValid() throws Exception {
        UUID account = UUID.randomUUID(),
                first = UUID.randomUUID(),
                second = UUID.randomUUID(),
                other = UUID.randomUUID();
        try (var c = runtime()) {
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), first, 0);
            context(c, account, "release");
            releaseFence(c, account, first, 1);
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), second, 2);
            c.commit();
            context(c, account, "release");
            releaseFence(c, account, second, 3);
            c.commit();
            managedContext(c, account, other);
            try (var q =
                    c.prepareStatement(
                            "UPDATE managed_payment_account_gate SET"
                                + " source_revision=source_revision+1 WHERE account_id=?")) {
                q.setObject(1, account);
                assertThat(q.executeUpdate()).isEqualTo(1);
            }
            c.commit();
            context(c, account, "read");
            try (var q =
                    c.prepareStatement(
                            "SELECT fence_version,active_fence_id,source_revision,(SELECT count(*)"
                                + " FROM managed_payment_account_history WHERE account_id=?) FROM"
                                + " managed_payment_account_gate WHERE account_id=?")) {
                q.setObject(1, account);
                q.setObject(2, account);
                try (var rows = q.executeQuery()) {
                    rows.next();
                    assertThat(rows.getLong(1)).isEqualTo(4);
                    assertThat(rows.getObject(2)).isNull();
                    assertThat(rows.getLong(3)).isEqualTo(1);
                    assertThat(rows.getLong(4)).isEqualTo(4);
                }
            }
            c.commit();
        }
    }

    @Test
    void nativeFenceExistsAndExactActionSurvivesConnectionRestart() throws Exception {
        UUID account = UUID.randomUUID(), action = UUID.randomUUID(), closure = UUID.randomUUID();
        String retained;
        try (var c = runtime()) {
            context(c, account, "quiesce");
            retained = quiesce(c, account, action, closure, 0);
            c.commit();
        }
        try (var c = runtime()) {
            context(c, account, "quiesce");
            assertThat(quiesce(c, account, action, closure, 0)).isEqualTo(retained);
            c.commit();
        }
    }

    @Test
    void defaultContextCannotReadFenceRows() throws Exception {
        try (var c = runtime();
                var s = c.createStatement();
                var rows = s.executeQuery("SELECT count(*) FROM managed_payment_account_fence")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isZero();
        }
    }

    @Test
    void closurePermissionDoesNotExposeOrdinaryCounterpartyPayloads() throws Exception {
        try (var c = runtime()) {
            context(c, ACCOUNT, "observe");
            try (var s = c.createStatement();
                    var rows = s.executeQuery("SELECT count(*) FROM managed_payment_operation")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
            c.rollback();
        }
    }

    @Test
    void runtimeIsNotGuardRoleMemberOrTableOwner() throws Exception {
        try (var c = runtime();
                var s = c.createStatement();
                var rows =
                        s.executeQuery(
                                "SELECT NOT rolsuper AND NOT rolbypassrls AND NOT"
                                    + " pg_has_role(current_user,'payment_closure_guard','MEMBER')"
                                    + " FROM pg_roles WHERE rolname=current_user")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBoolean(1)).isTrue();
        }
    }

    static void managedContext(Connection c, UUID source, UUID destination) throws SQLException {
        c.setAutoCommit(false);
        try (var q =
                c.prepareStatement(
                        "SELECT"
                            + " set_config('firefly.payment.tenant','owned',true),set_config('firefly.payment.accounts',?,true),"
                            + "set_config('firefly.payment.purpose','OBSERVE',true),set_config('firefly.payment.xid',pg_current_xact_id()::text,true),set_config('firefly.payment.until',(floor(extract(epoch"
                            + " FROM clock_timestamp())*1000)::bigint+30000)::text,true)")) {
            q.setString(1, "[\"" + source + "\",\"" + destination + "\"]");
            q.execute();
        }
    }

    static UUID seedOperation(
            Connection c,
            UUID source,
            UUID destination,
            String state,
            String effectState,
            String kind)
            throws SQLException {
        return seedOperation(
                c,
                source,
                destination,
                state,
                effectState,
                kind,
                java.util.function.UnaryOperator.identity());
    }

    static UUID seedOperation(
            Connection c,
            UUID source,
            UUID destination,
            String state,
            String effectState,
            String kind,
            java.util.function.UnaryOperator<String> amend)
            throws SQLException {
        UUID payment = UUID.randomUUID(),
                action = UUID.randomUUID(),
                reserve = UUID.randomUUID(),
                effect = kind.equals("RESERVE") ? reserve : UUID.randomUUID();
        String payload =
                "{\"paymentId\":\""
                        + payment
                        + "\",\"createActionId\":\""
                        + action
                        + "\",\"tenant\":\"owned\","
                        + "\"sourceAccountId\":\""
                        + source
                        + "\",\"destinationAccountId\":\""
                        + destination
                        + "\",\"amount\":\"1.00\","
                        + "\"currency\":\"USD\",\"currencyScale\":2,\"currencyPolicy\":\"USD-2026\",\"ttlSeconds\":900,"
                        + "\"notAfterEpochMillis\":"
                        + (System.currentTimeMillis() + 900000)
                        + ",\"initiator\":\"native-test\",\"state\":\""
                        + state
                        + "\",\"version\":0,"
                        + "\"pendingEffectId\":"
                        + (effectState.equals("ACKNOWLEDGED") ? "null" : "\"" + effect + "\"")
                        + ","
                        + "\"reservationId\":"
                        + (state.equals("RECEIVED") || state.equals("RECOVERY_REQUIRED")
                                ? "null"
                                : "\"" + UUID.randomUUID() + "\"")
                        + ","
                        + "\"postingId\":"
                        + (state.equals("COMPLETED") ? "\"" + UUID.randomUUID() + "\"" : "null")
                        + ",\"ledgerState\":"
                        + (state.equals("RESERVED")
                                ? "\"HELD\""
                                : state.equals("COMPLETED") ? "\"COMMITTED\"" : "null")
                        + ",\"ledgerVersion\":1,\"observedAt\":\"2026-01-01T00:00:00Z\"}";
        payload = amend.apply(payload);
        try (var q =
                c.prepareStatement(
                        "INSERT INTO"
                            + " managed_payment_operation(tenant_id,payment_id,source_account_id,destination_account_id,create_action_id,request_digest,payload,state,version)"
                            + " VALUES('owned',?,?,?,?,?,CAST(? AS jsonb),?,0)")) {
            q.setObject(1, payment);
            q.setObject(2, source);
            q.setObject(3, destination);
            q.setObject(4, action);
            q.setString(5, "b".repeat(64));
            q.setString(6, payload);
            q.setString(7, state);
            q.executeUpdate();
        }
        try (var q =
                c.prepareStatement(
                        "INSERT INTO"
                            + " managed_payment_action(tenant_id,payment_id,source_account_id,destination_account_id,action_id,kind,intent_digest,actor_subject)"
                            + " VALUES('owned',?,?,?,?,'CREATE',?,'native-test')")) {
            q.setObject(1, payment);
            q.setObject(2, source);
            q.setObject(3, destination);
            q.setObject(4, action);
            q.setString(5, "b".repeat(64));
            q.executeUpdate();
        }
        for (String effectKind :
                kind.equals("RESERVE") ? List.of("RESERVE") : List.of("RESERVE", kind)) {
            String retainedState = effectKind.equals(kind) ? effectState : "ACKNOWLEDGED";
            try (var q =
                    c.prepareStatement(
                            "INSERT INTO"
                                + " managed_payment_effect(tenant_id,payment_id,source_account_id,destination_account_id,effect_id,participant_action_id,kind,state,owner_subject,lease_until)"
                                + " VALUES('owned',?,?,?,?,?,?,?,CASE WHEN ?='CLAIMED' THEN"
                                + " 'worker' END,CASE WHEN ?='CLAIMED' THEN"
                                + " clock_timestamp()+interval '1 hour' END)")) {
                q.setObject(1, payment);
                q.setObject(2, source);
                q.setObject(3, destination);
                q.setObject(4, effectKind.equals("RESERVE") ? reserve : effect);
                q.setObject(5, UUID.randomUUID());
                q.setString(6, effectKind);
                q.setString(7, retainedState);
                q.setString(8, retainedState);
                q.setString(9, retainedState);
                q.executeUpdate();
            }
        }
        for (String table : List.of("projection", "history", "outbox")) {
            String columns = table.equals("projection") ? "payload" : "version,payload";
            String values = table.equals("projection") ? "CAST(? AS jsonb)" : "0,CAST(? AS jsonb)";
            if (table.equals("outbox")) {
                columns = "version,event_id,payload";
                values = "0,gen_random_uuid(),CAST(? AS jsonb)";
            }
            try (var q =
                    c.prepareStatement(
                            "INSERT INTO managed_payment_"
                                    + table
                                    + "(tenant_id,payment_id,source_account_id,destination_account_id,"
                                    + columns
                                    + ") VALUES('owned',?,?,?,"
                                    + values
                                    + ")")) {
                q.setObject(1, payment);
                q.setObject(2, source);
                q.setObject(3, destination);
                q.setString(4, payload);
                q.executeUpdate();
            }
        }
        return payment;
    }

    static String capture(Connection c, UUID account, UUID closure, UUID observation)
            throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT public.managed_payment_account_capture('owned',?,?,?)::text")) {
            q.setObject(1, account);
            q.setObject(2, closure);
            q.setObject(3, observation);
            try (var rows = q.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    @Test
    void retainedAbsenceRequiresExactReceiptAndFixedDeadlineProof() throws Exception {
        for (String flaw :
                List.of(
                        "VALID",
                        "AT_DEADLINE",
                        "MISSING_FINANCIAL",
                        "PENDING_EFFECT",
                        "MISSING_RECEIPT",
                        "MALFORMED_RECEIPT",
                        "PRE_DEADLINE",
                        "RESERVATION",
                        "LEDGER_VERSION",
                        "POSTING",
                        "REVERSAL")) {
            UUID account = UUID.randomUUID(),
                    other = UUID.randomUUID(),
                    closure = UUID.randomUUID();
            try (var c = runtime()) {
                managedContext(c, account, other);
                java.util.concurrent.Callable<UUID> seed =
                        () ->
                                seedOperation(
                                        c,
                                        other,
                                        account,
                                        "EXPIRED",
                                        flaw.equals("PENDING_EFFECT") ? "PENDING" : "ACKNOWLEDGED",
                                        "RESERVE",
                                        raw -> {
                                            try {
                                                var data =
                                                        (com.fasterxml.jackson.databind.node
                                                                        .ObjectNode)
                                                                new com.fasterxml.jackson.databind
                                                                                .ObjectMapper()
                                                                        .readTree(raw);
                                                data.put(
                                                        "notAfterEpochMillis",
                                                        System.currentTimeMillis() - 5000);
                                                data.put("ledgerState", "ABSENT_CLOSED");
                                                data.put(
                                                        "absenceReceiptId",
                                                        UUID.randomUUID().toString());
                                                data.put(
                                                        "observedAt",
                                                        java.time.Instant.now().toString());
                                                for (String field :
                                                        List.of(
                                                                "reservationId",
                                                                "ledgerVersion",
                                                                "postingId",
                                                                "reversalId")) data.putNull(field);
                                                switch (flaw) {
                                                    case "MISSING_FINANCIAL" ->
                                                            data.remove("ledgerVersion");
                                                    case "AT_DEADLINE" ->
                                                            data.put(
                                                                    "observedAt",
                                                                    java.time.Instant.ofEpochMilli(
                                                                                    data.path(
                                                                                                    "notAfterEpochMillis")
                                                                                            .asLong())
                                                                            .toString());
                                                    case "MISSING_RECEIPT" ->
                                                            data.putNull("absenceReceiptId");
                                                    case "MALFORMED_RECEIPT" ->
                                                            data.put(
                                                                    "absenceReceiptId",
                                                                    "malformed");
                                                    case "PRE_DEADLINE" ->
                                                            data.put(
                                                                    "observedAt",
                                                                    java.time.Instant.now()
                                                                            .minusSeconds(10)
                                                                            .toString());
                                                    case "RESERVATION" ->
                                                            data.put(
                                                                    "reservationId",
                                                                    UUID.randomUUID().toString());
                                                    case "LEDGER_VERSION" ->
                                                            data.put("ledgerVersion", 7);
                                                    case "POSTING" ->
                                                            data.put(
                                                                    "postingId",
                                                                    UUID.randomUUID().toString());
                                                    case "REVERSAL" ->
                                                            data.put(
                                                                    "reversalId",
                                                                    UUID.randomUUID().toString());
                                                }
                                                return data.toString();
                                            } catch (
                                                    com.fasterxml.jackson.core
                                                                    .JsonProcessingException
                                                            error) {
                                                throw new IllegalArgumentException(error);
                                            }
                                        });
                if (Set.of("MISSING_RECEIPT", "MALFORMED_RECEIPT").contains(flaw)) {
                    assertThatThrownBy(seed::call)
                            .as(flaw)
                            .isInstanceOfSatisfying(
                                    SQLException.class,
                                    error ->
                                            assertThat(error.getSQLState())
                                                    .isEqualTo(
                                                            flaw.equals("MISSING_RECEIPT")
                                                                    ? "23514"
                                                                    : "22P02"));
                    c.rollback();
                    continue;
                }
                seed.call();
                c.commit();
                context(c, account, "quiesce");
                quiesce(c, account, UUID.randomUUID(), closure, 0);
                c.commit();
                context(c, account, "observe");
                var receipt =
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(capture(c, account, closure, UUID.randomUUID()));
                assertThat(receipt.path("status").asText())
                        .as(flaw)
                        .isEqualTo(
                                Set.of("VALID", "AT_DEADLINE").contains(flaw)
                                        ? "CLEAR"
                                        : flaw.equals("PENDING_EFFECT")
                                                ? "OBLIGATIONS"
                                                : "UNKNOWN");
                c.commit();
            }
        }
    }

    @Test
    void completeObservationIncludesFutureIncomingLeasedAndExhaustedInstructions()
            throws Exception {
        UUID account = UUID.randomUUID(),
                other = UUID.randomUUID(),
                closure = UUID.randomUUID(),
                observation = UUID.randomUUID();
        try (var c = runtime()) {
            managedContext(c, account, other);
            seedOperation(c, account, other, "RECEIVED", "PENDING", "RESERVE");
            seedOperation(c, other, account, "RESERVED", "ACKNOWLEDGED", "RESERVE");
            seedOperation(c, account, other, "COMMITTING", "CLAIMED", "COMMIT");
            seedOperation(c, other, account, "CANCELLING", "CLAIMED", "RELEASE");
            seedOperation(c, other, account, "RECOVERY_REQUIRED", "EXHAUSTED", "RESERVE");
            seedOperation(c, account, other, "COMPLETED", "CLAIMED", "OBSERVE");
            c.commit();
        }
        try (var c = runtime()) {
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), closure, 0);
            c.commit();
            context(c, account, "observe");
            assertThat(capture(c, account, closure, observation)).contains("OBLIGATIONS");
            try (var q =
                    c.prepareStatement(
                            "SELECT count(*) FROM managed_payment_account_observation_member WHERE"
                                    + " tenant_id='owned' AND observation_id=?")) {
                q.setObject(1, observation);
                try (var rows = q.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(6);
                }
            }
            c.commit();
        }
    }

    @Test
    void managedServiceReportsExactNativeFenceRejectionAsConflict() throws Exception {
        UUID account = UUID.randomUUID(), other = UUID.randomUUID();
        try (var c = runtime()) {
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), UUID.randomUUID(), 0);
            c.commit();
        }
        var factory =
                ConnectionFactories.get(
                        "r2dbc:postgresql://closure_runtime:synthetic-owned-runtime@"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/closure_native");
        var db = DatabaseClient.create(factory);
        var unrelated =
                new org.springframework.dao.DataAccessResourceFailureException(
                        "owned unrelated failure");
        var failPeer = new java.util.concurrent.atomic.AtomicBoolean();
        var ledger =
                new LedgerFundsObservationPort() {
                    public Mono<EnrolledPair> accounts(
                            String tenant, UUID source, UUID destination) {
                        return failPeer.get()
                                ? Mono.error(unrelated)
                                : Mono.just(
                                        new EnrolledPair(
                                                source, destination, "USD", 2, "USD-2026"));
                    }

                    public Mono<VerifiedFundsObservation> observe(
                            String tenant,
                            io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                                            .ManagedPaymentContracts.View
                                    value) {
                        return Mono.error(new UnsupportedOperationException());
                    }
                };
        try (var persistence = OwnedPaymentPersistence.create(db)) {
        var service = persistence.managedService(ledger, new R2dbcTransactionManager(factory));
        var actor =
                SecurityPrincipal.builder()
                        .subject("native-worker")
                        .issuer("https://owned.invalid")
                        .tenantId("owned")
                        .scopes(Set.of("payments:create", "funds:read"))
                        .claims(
                                Map.of(
                                        "exp",
                                        java.time.Instant.now().plusSeconds(60),
                                        "funds_accounts",
                                        List.of(account.toString(), other.toString())))
                        .build();
        var call =
                new io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicy
                        .Call(actor, "owned-unused-test-bearer", Set.of());
        for (boolean incoming : List.of(true, false)) {
            var input =
                    new io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                            .ManagedPaymentContracts.Create(
                            UUID.randomUUID(),
                            incoming ? other : account,
                            incoming ? account : other,
                            new java.math.BigDecimal("1.00"),
                            "USD",
                            300,
                            null);
            assertThatThrownBy(
                            () ->
                                    service.create("owned", input)
                                            .contextWrite(
                                                    c ->
                                                            io.fireflysoftware.core.banking.payments
                                                                    .core.managed.impl.ManagedPaymentPolicy.with(
                                                                    c, call))
                                            .block(java.time.Duration.ofSeconds(10)))
                    .isInstanceOfSatisfying(
                            org.springframework.web.server.ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
            failPeer.set(true);
            assertThatThrownBy(
                            () ->
                                    service.create("owned", input)
                                            .contextWrite(
                                                    c ->
                                                            io.fireflysoftware.core.banking.payments
                                                                    .core.managed.impl.ManagedPaymentPolicy.with(
                                                                    c, call))
                                            .block(java.time.Duration.ofSeconds(10)))
                    .isSameAs(unrelated);
            failPeer.set(false);
        }
        try (var c = admin();
                var st = c.createStatement()) {
            st.execute(
                    "CREATE FUNCTION owned_intake_unrelated_failure() RETURNS trigger LANGUAGE"
                        + " plpgsql AS $$ BEGIN RAISE EXCEPTION 'owned unrelated native failure';"
                        + " END $$");
            st.execute(
                    "CREATE TRIGGER aaa_owned_intake_unrelated_failure BEFORE INSERT ON"
                            + " managed_payment_operation FOR EACH ROW EXECUTE FUNCTION"
                            + " owned_intake_unrelated_failure()");
        }
        try {
            var input =
                    new io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                            .ManagedPaymentContracts.Create(
                            UUID.randomUUID(),
                            account,
                            other,
                            new java.math.BigDecimal("1.00"),
                            "USD",
                            300,
                            null);
            assertThatThrownBy(
                            () ->
                                    service.create("owned", input)
                                            .contextWrite(
                                                    c ->
                                                            io.fireflysoftware.core.banking.payments
                                                                    .core.managed.impl.ManagedPaymentPolicy.with(
                                                                    c, call))
                                            .block(java.time.Duration.ofSeconds(10)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .satisfies(
                            error -> {
                                Throwable cause = error;
                                while (cause.getCause() != null) cause = cause.getCause();
                                assertThat(cause)
                                        .isInstanceOfSatisfying(
                                                io.r2dbc.spi.R2dbcException.class,
                                                sql -> {
                                                    assertThat(sql.getSqlState())
                                                            .isEqualTo("P0001");
                                                    assertThat(sql.getMessage())
                                                            .isEqualTo(
                                                                    "owned unrelated native"
                                                                            + " failure");
                                                });
                            });
        } finally {
            try (var c = admin();
                    var st = c.createStatement()) {
                st.execute(
                        "DROP TRIGGER aaa_owned_intake_unrelated_failure ON"
                                + " managed_payment_operation");
                st.execute("DROP FUNCTION owned_intake_unrelated_failure()");
            }
        }
        }
    }

    @Test
    void fencedAccountRejectsNewIncomingAndOutgoingInstructions() throws Exception {
        UUID account = UUID.randomUUID(), other = UUID.randomUUID(), closure = UUID.randomUUID();
        try (var c = runtime()) {
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), closure, 0);
            c.commit();
        }
        for (boolean incoming : List.of(false, true))
            try (var c = runtime()) {
                managedContext(c, account, other);
                assertThatThrownBy(
                                () ->
                                        seedOperation(
                                                c,
                                                incoming ? other : account,
                                                incoming ? account : other,
                                                "RECEIVED",
                                                "PENDING",
                                                "RESERVE"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("QUIESCED");
                c.rollback();
            }
    }

    @Test
    void pendingObservationCannotBeAddedToCompletedPaymentAfterClearance() throws Exception {
        UUID account = UUID.randomUUID(),
                other = UUID.randomUUID(),
                closure = UUID.randomUUID(),
                payment;
        try (var c = runtime()) {
            managedContext(c, account, other);
            payment = seedOperation(c, account, other, "COMPLETED", "ACKNOWLEDGED", "OBSERVE");
            c.commit();
        }
        try (var c = runtime()) {
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), closure, 0);
            c.commit();
            context(c, account, "observe");
            assertThat(capture(c, account, closure, UUID.randomUUID())).contains("CLEAR");
            c.commit();
        }
        try (var c = runtime()) {
            managedContext(c, account, other);
            assertThatThrownBy(
                            () -> {
                                try (var q =
                                        c.prepareStatement(
                                                "UPDATE managed_payment_effect SET state='PENDING'"
                                                    + " WHERE tenant_id='owned' AND payment_id=?"
                                                    + " AND kind='OBSERVE'")) {
                                    q.setObject(1, payment);
                                    q.executeUpdate();
                                }
                            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("QUIESCED");
            c.rollback();
        }
    }

    static String release(Connection c, UUID account, UUID action, UUID closure, long expected)
            throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT public.managed_payment_account_release('owned',?,?,?,?,?)::text")) {
            q.setObject(1, account);
            q.setObject(2, action);
            q.setObject(3, closure);
            q.setLong(4, expected);
            q.setString(5, "c".repeat(64));
            try (var r = q.executeQuery()) {
                assertThat(r.next()).isTrue();
                return r.getString(1);
            }
        }
    }

    @Test
    void explicitReleaseUsesCasAndKeepsOriginalReceiptsAndHistory() throws Exception {
        UUID account = UUID.randomUUID(), closure = UUID.randomUUID(), action = UUID.randomUUID();
        String receipt;
        try (var c = runtime()) {
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), closure, 0);
            c.commit();
            context(c, account, "release");
            receipt = release(c, account, action, closure, 1);
            c.commit();
        }
        try (var c = runtime()) {
            context(c, account, "release");
            assertThat(release(c, account, action, closure, 1)).isEqualTo(receipt);
            try (var q =
                    c.prepareStatement(
                            "SELECT count(*) FROM managed_payment_account_history WHERE"
                                    + " tenant_id='owned' AND account_id=?")) {
                q.setObject(1, account);
                try (var r = q.executeQuery()) {
                    r.next();
                    assertThat(r.getLong(1)).isEqualTo(2);
                }
            }
            c.commit();
        }
    }

    @Test
    void authorityExpiringWhileWaitingForGateCannotAdmitFence() throws Exception {
        UUID account = UUID.randomUUID();
        var started = new java.util.concurrent.CountDownLatch(1);
        try (var holder = runtime();
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            context(holder, account, "quiesce");
            try (var q =
                    holder.prepareStatement(
                            "SELECT public.managed_payment_account_gate('owned',?)")) {
                q.setObject(1, account);
                q.execute();
            }
            var waiting =
                    executor.submit(
                            () -> {
                                try (var c = runtime()) {
                                    context(c, account, "quiesce");
                                    try (var st = c.createStatement()) {
                                        st.execute(
                                                "SELECT"
                                                    + " set_config('firefly.payment_closure.until',(floor(extract(epoch"
                                                    + " FROM"
                                                    + " clock_timestamp())*1000)::bigint+200)::text,true)");
                                    }
                                    started.countDown();
                                    return quiesce(
                                            c, account, UUID.randomUUID(), UUID.randomUUID(), 0);
                                }
                            });
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Thread.sleep(350);
            holder.commit();
            assertThatThrownBy(() -> waiting.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SQLException.class);
        }
    }

    @Test
    void missingOutboxRollsBackFenceActionAndGateTogether() throws Exception {
        UUID account = UUID.randomUUID();
        try (var c = admin();
                var st = c.createStatement()) {
            st.execute(
                    "CREATE FUNCTION owned_closure_fail_outbox() RETURNS trigger LANGUAGE plpgsql"
                            + " AS $$ BEGIN RAISE EXCEPTION 'owned outbox unavailable'; END $$");
            st.execute(
                    "CREATE TRIGGER owned_closure_fail_outbox BEFORE INSERT ON"
                            + " managed_payment_account_outbox FOR EACH ROW EXECUTE FUNCTION"
                            + " owned_closure_fail_outbox()");
        }
        try {
            try (var c = runtime()) {
                context(c, account, "quiesce");
                assertThatThrownBy(
                                () -> quiesce(c, account, UUID.randomUUID(), UUID.randomUUID(), 0))
                        .isInstanceOf(SQLException.class);
                c.rollback();
            }
            try (var c = admin();
                    var q =
                            c.prepareStatement(
                                    "SELECT (SELECT count(*) FROM managed_payment_account_fence"
                                        + " WHERE account_id=?)+(SELECT count(*) FROM"
                                        + " managed_payment_account_action WHERE"
                                        + " account_id=?)+(SELECT count(*) FROM"
                                        + " managed_payment_account_gate WHERE account_id=?)")) {
                q.setObject(1, account);
                q.setObject(2, account);
                q.setObject(3, account);
                try (var r = q.executeQuery()) {
                    r.next();
                    assertThat(r.getLong(1)).isZero();
                }
            }
        } finally {
            try (var c = admin();
                    var st = c.createStatement()) {
                st.execute(
                        "DROP TRIGGER owned_closure_fail_outbox ON managed_payment_account_outbox");
                st.execute("DROP FUNCTION owned_closure_fail_outbox()");
            }
        }
    }

    PaymentAccountQuiescenceService service(
            UUID account, java.util.concurrent.atomic.AtomicBoolean allowed) {
        var beans = new StaticListableBeanFactory();
        var principal =
                SecurityPrincipal.builder()
                        .subject("native-worker")
                        .issuer("https://owned.invalid")
                        .tenantId("owned")
                        .scopes(
                                Set.of(
                                        "payments:closure:quiesce",
                                        "payments:closure:read",
                                        "payments:closure:observe",
                                        "payments:closure:release"))
                        .claims(
                                Map.of(
                                        "exp",
                                        java.time.Instant.now().plusSeconds(60),
                                        "funds_accounts",
                                        List.of(account.toString())))
                        .build();
        beans.addBean("context", (SecurityContextPort) () -> Mono.just(principal));
        beans.addBean(
                "policy",
                (PolicyDecisionPort)
                        (actor, action, resource, exact) ->
                                Mono.just(
                                        allowed.get()
                                                ? Decision.permit()
                                                : Decision.deny("withdrawn")));
        var authority =
                new PaymentAccountQuiescenceAuthority(
                        beans.getBeanProvider(SecurityContextPort.class),
                        beans.getBeanProvider(PolicyDecisionPort.class),
                        new MockEnvironment()
                                .withProperty(
                                        "firefly.security.resource-server.strict-policy", "true"));
        var factory =
                ConnectionFactories.get(
                        "r2dbc:postgresql://closure_runtime:synthetic-owned-runtime@"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/closure_native");
        var db = DatabaseClient.create(factory);
        var ledger =
                new LedgerFundsObservationPort() {
                    public Mono<EnrolledPair> accounts(String t, UUID a, UUID b) {
                        return Mono.error(new UnsupportedOperationException());
                    }

                    public Mono<VerifiedFundsObservation> observe(
                            String t,
                            io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                                            .ManagedPaymentContracts.View
                                    v) {
                        return Mono.error(new UnsupportedOperationException());
                    }

                    public Mono<EnrolledAccount> closureAccount(String t, UUID a) {
                        return Mono.just(
                                new EnrolledAccount(
                                        a,
                                        ENROLLMENT,
                                        CONTRACT,
                                        "USD",
                                        2,
                                        "USD-2026",
                                        7,
                                        "BLOCKED"));
                    }
                };
        var persistence = OwnedPaymentPersistence.create(db);
        return new PaymentAccountQuiescenceServiceImpl(
                persistence.closureStore(),
                persistence.closureContext(),
                authority,
                ledger,
                new R2dbcTransactionManager(factory));
    }

    @Test
    void finiteClosureScopeDoesNotSurviveCommitRollbackOrCancellationOnPooledConnection()
            throws Exception {
        UUID account = UUID.randomUUID();
        try (var c = runtime()) {
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), UUID.randomUUID(), 0);
            c.commit();
        }
        var factory =
                ConnectionFactories.get(
                        "r2dbc:postgresql://closure_runtime:synthetic-owned-runtime@"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/closure_native");
        var pool =
                new io.r2dbc.pool.ConnectionPool(
                        io.r2dbc.pool.ConnectionPoolConfiguration.builder(factory)
                                .initialSize(0)
                                .maxSize(1)
                                .maxAcquireTime(java.time.Duration.ofSeconds(5))
                                .build());
        var db = DatabaseClient.create(pool);
        var tx =
                org.springframework.transaction.reactive.TransactionalOperator.create(
                        new R2dbcTransactionManager(pool));
        java.util.function.Supplier<Mono<Integer>> scoped =
                () ->
                        db.sql(
                                        """
                                        SELECT set_config('firefly.payment_closure.tenant','owned',true),
                                          set_config('firefly.payment_closure.account',:account,true),
                                          set_config('firefly.payment_closure.purpose','read',true),
                                          set_config('firefly.payment_closure.xid',pg_current_xact_id()::text,true),
                                          set_config('firefly.payment_closure.until',(floor(extract(epoch FROM clock_timestamp())*1000)::bigint+30000)::text,true),
                                          set_config('firefly.payment_closure.actor_issuer','https://owned.invalid',true),
                                          set_config('firefly.payment_closure.actor_subject','native-pool-test',true)
                                        """)
                                .bind("account", account.toString())
                                .map((row, metadata) -> 0)
                                .one()
                                .then(
                                        db.sql(
                                                        "SELECT pg_backend_pid() AS pid,(SELECT"
                                                            + " count(*) FROM"
                                                            + " managed_payment_account_fence) AS"
                                                            + " visible")
                                                .map(
                                                        (row, metadata) -> {
                                                            assertThat(
                                                                            row.get(
                                                                                    "visible",
                                                                                    Long.class))
                                                                    .isEqualTo(1);
                                                            return row.get("pid", Integer.class);
                                                        })
                                                .one());
        java.util.function.Supplier<Mono<Integer>> clean =
                () ->
                        db.sql(
                                        """
                                        SELECT pg_backend_pid() AS pid,
                                          (SELECT count(*) FROM managed_payment_account_fence) AS visible,
                                          (SELECT bool_and(nullif(current_setting(name,true),'') IS NULL)
                                            FROM unnest(ARRAY['firefly.payment_closure.tenant','firefly.payment_closure.account',
                                              'firefly.payment_closure.purpose','firefly.payment_closure.xid','firefly.payment_closure.until',
                                              'firefly.payment_closure.actor_issuer','firefly.payment_closure.actor_subject']) AS names(name)) AS clean
                                        """)
                                .map(
                                        (row, metadata) -> {
                                            assertThat(row.get("visible", Long.class)).isZero();
                                            assertThat(row.get("clean", Boolean.class)).isTrue();
                                            return row.get("pid", Integer.class);
                                        })
                                .one();
        try {
            int pid = scoped.get().as(tx::transactional).block(java.time.Duration.ofSeconds(10));
            assertThat(clean.get().block(java.time.Duration.ofSeconds(10))).isEqualTo(pid);
            assertThatThrownBy(
                            () ->
                                    scoped.get()
                                            .then(
                                                    Mono.error(
                                                            new IllegalStateException(
                                                                    "owned rollback")))
                                            .as(tx::transactional)
                                            .block(java.time.Duration.ofSeconds(10)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("owned rollback");
            assertThat(clean.get().block(java.time.Duration.ofSeconds(10))).isEqualTo(pid);
            var ready = new java.util.concurrent.CountDownLatch(1);
            var subscription =
                    scoped.get()
                            .doOnNext(value -> ready.countDown())
                            .then(Mono.never())
                            .as(tx::transactional)
                            .subscribe();
            try {
                assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally {
                subscription.dispose();
            }
            assertThat(clean.get().block(java.time.Duration.ofSeconds(10))).isEqualTo(pid);
        } finally {
            pool.disposeLater().block(java.time.Duration.ofSeconds(10));
        }
    }

    @Test
    void nativeTypedServiceReauthorizesReplayAndInvalidatesCurrentClearanceOnRelease() {
        UUID account = UUID.randomUUID(), closure = UUID.randomUUID(), action = UUID.randomUUID();
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var service = service(account, allowed);
        byte[] raw =
                ("{\"actionId\":\""
                                + action
                                + "\",\"closureId\":\""
                                + closure
                                + "\",\"enrollmentId\":\""
                                + ENROLLMENT
                                + "\",\"expectedFenceVersion\":0}")
                        .getBytes(StandardCharsets.UTF_8);
        var accepted =
                service.quiesce("owned", account, raw).block(java.time.Duration.ofSeconds(10));
        assertThat(accepted).isNotNull();
        assertThat(accepted.selection().contractId()).isEqualTo(CONTRACT);
        assertThat(accepted.observedLedgerVersion()).isEqualTo(7);
        assertThat(service.quiesce("owned", account, raw).block()).isEqualTo(accepted);
        allowed.set(false);
        assertThatThrownBy(() -> service.quiesce("owned", account, raw).block())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        allowed.set(true);
        assertThat(service.get("owned", account, closure).block().observation())
                .isEqualTo(ObservationStatus.UNKNOWN);
        var observed = service.obligations("owned", account, closure, null, 0, 100).block();
        assertThat(observed.observation().status()).isEqualTo(ObservationStatus.CLEAR);
        byte[] release =
                ("{\"actionId\":\""
                                + UUID.randomUUID()
                                + "\",\"closureId\":\""
                                + closure
                                + "\",\"expectedFenceVersion\":1}")
                        .getBytes(StandardCharsets.UTF_8);
        service.release("owned", account, release).block();
        assertThat(service.get("owned", account, closure).block().observation())
                .isEqualTo(ObservationStatus.UNKNOWN);
        assertThatThrownBy(
                        () ->
                                service.obligations(
                                                "owned",
                                                account,
                                                closure,
                                                observed.observation().observationId(),
                                                0,
                                                100)
                                        .block())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void createWinningAccountGatesIsRetainedByLaterFence() throws Exception {
        UUID account = UUID.randomUUID(), other = UUID.randomUUID(), closure = UUID.randomUUID();
        try (var creator = runtime();
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            managedContext(creator, account, other);
            seedOperation(creator, account, other, "RECEIVED", "PENDING", "RESERVE");
            var started = new java.util.concurrent.CountDownLatch(1);
            var fencer =
                    executor.submit(
                            () -> {
                                try (var c = runtime()) {
                                    context(c, account, "quiesce");
                                    started.countDown();
                                    var receipt =
                                            quiesce(c, account, UUID.randomUUID(), closure, 0);
                                    c.commit();
                                    return receipt;
                                }
                            });
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> fencer.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            creator.commit();
            assertThat(fencer.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .contains(closure.toString());
        }
        try (var c = runtime()) {
            context(c, account, "observe");
            assertThat(capture(c, account, closure, UUID.randomUUID())).contains("OBLIGATIONS");
            c.commit();
        }
    }

    @Test
    void fenceWinningAccountGatesRejectsOppositeEndpointCreation() throws Exception {
        UUID account = UUID.randomUUID(), other = UUID.randomUUID(), closure = UUID.randomUUID();
        try (var fencer = runtime();
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            context(fencer, account, "quiesce");
            quiesce(fencer, account, UUID.randomUUID(), closure, 0);
            var started = new java.util.concurrent.CountDownLatch(1);
            var creator =
                    executor.submit(
                            () -> {
                                try (var c = runtime()) {
                                    managedContext(c, other, account);
                                    started.countDown();
                                    seedOperation(
                                            c, other, account, "RECEIVED", "PENDING", "RESERVE");
                                    c.commit();
                                    return true;
                                }
                            });
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> creator.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            fencer.commit();
            assertThatThrownBy(() -> creator.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SQLException.class);
        }
    }

    @Test
    void summaryPrivilegeCannotReadRetainedPrivateProducerReferences() throws Exception {
        UUID account = UUID.randomUUID(), other = UUID.randomUUID(), closure = UUID.randomUUID();
        try (var c = runtime()) {
            managedContext(c, other, account);
            seedOperation(c, other, account, "RECEIVED", "PENDING", "RESERVE");
            c.commit();
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), closure, 0);
            c.commit();
            context(c, account, "observe");
            capture(c, account, closure, UUID.randomUUID());
            for (String table :
                    List.of(
                            "managed_payment_operation",
                            "managed_payment_account_observation_reference"))
                try (var q = c.createStatement();
                        var r = q.executeQuery("SELECT count(*) FROM " + table)) {
                    r.next();
                    assertThat(r.getLong(1)).isZero();
                }
            try (var q = c.createStatement();
                    var r =
                            q.executeQuery(
                                    "SELECT * FROM managed_payment_account_observation_member")) {
                assertThat(r.next()).isTrue();
                var names = new ArrayList<String>();
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                    names.add(r.getMetaData().getColumnName(i));
                assertThat(names)
                        .doesNotContain(
                                "payment_id",
                                "source_account_id",
                                "destination_account_id",
                                "amount");
            }
            c.commit();
        }
    }

    @Test
    void immutableCapturePagesDoNotChangeWhenRetainedEffectIsClaimed() throws Exception {
        UUID account = UUID.randomUUID(),
                other = UUID.randomUUID(),
                closure = UUID.randomUUID(),
                observation = UUID.randomUUID(),
                payment;
        try (var c = runtime()) {
            managedContext(c, account, other);
            payment = seedOperation(c, account, other, "RECEIVED", "PENDING", "RESERVE");
            seedOperation(c, other, account, "RECEIVED", "PENDING", "RESERVE");
            c.commit();
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), closure, 0);
            c.commit();
            context(c, account, "observe");
            capture(c, account, closure, observation);
            c.commit();
        }
        var service = service(account, new java.util.concurrent.atomic.AtomicBoolean(true));
        var first = service.obligations("owned", account, closure, observation, 0, 1).block();
        assertThat(first.nextAfter()).isNotNull();
        try (var c = runtime()) {
            managedContext(c, account, other);
            try (var q =
                    c.prepareStatement(
                            "UPDATE managed_payment_effect SET"
                                + " state='CLAIMED',fence=fence+1,attempts=attempts+1,owner_subject='native-worker',lease_until=clock_timestamp()+interval"
                                + " '1 hour' WHERE tenant_id='owned' AND payment_id=? AND"
                                + " kind='RESERVE'")) {
                q.setObject(1, payment);
                q.executeUpdate();
            }
            c.commit();
        }
        assertThat(service.obligations("owned", account, closure, observation, 0, 1).block())
                .isEqualTo(first);
        var second =
                service.obligations("owned", account, closure, observation, first.nextAfter(), 1)
                        .block();
        assertThat(second.observation()).isEqualTo(first.observation());
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().effectState()).isEqualTo("PENDING");
    }

    @Test
    void ordinaryManagedContextCannotRemoveStickyFenceByChangingAccountGate() throws Exception {
        UUID account = UUID.randomUUID(), other = UUID.randomUUID(), closure = UUID.randomUUID();
        try (var c = runtime()) {
            context(c, account, "quiesce");
            quiesce(c, account, UUID.randomUUID(), closure, 0);
            c.commit();
        }
        try (var c = runtime()) {
            managedContext(c, account, other);
            assertThatThrownBy(
                            () -> {
                                try (var q =
                                        c.prepareStatement(
                                                "UPDATE managed_payment_account_gate SET"
                                                    + " active_fence_id=NULL,fence_version=fence_version+1"
                                                    + " WHERE tenant_id='owned' AND"
                                                    + " account_id=?")) {
                                    q.setObject(1, account);
                                    q.executeUpdate();
                                }
                            })
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
    }

    @Test
    void sameActionOnAnotherAuthorizedAccountIsConflictAndRollsBack() {
        UUID action = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        String template =
                "{\"actionId\":\""
                        + action
                        + "\",\"closureId\":\"%s\",\"enrollmentId\":\""
                        + ENROLLMENT
                        + "\",\"expectedFenceVersion\":0}";
        service(first, allowed)
                .quiesce(
                        "owned",
                        first,
                        template.formatted(UUID.randomUUID()).getBytes(StandardCharsets.UTF_8))
                .block();
        assertThatThrownBy(
                        () ->
                                service(second, allowed)
                                        .quiesce(
                                                "owned",
                                                second,
                                                template.formatted(UUID.randomUUID())
                                                        .getBytes(StandardCharsets.UTF_8))
                                        .block())
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }
}
