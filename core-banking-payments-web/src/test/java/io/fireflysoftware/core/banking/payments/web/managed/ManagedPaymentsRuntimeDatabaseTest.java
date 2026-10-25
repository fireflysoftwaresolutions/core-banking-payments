/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package io.fireflysoftware.core.banking.payments.web.managed;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.*;

import io.fireflysoftware.core.banking.payments.web.PaymentsApplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;

@Testcontainers
@SpringBootTest(
        classes = {PaymentsApplication.class, ManagedPaymentsRuntimeDatabaseTest.CacheScope.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "firefly.eda.enabled=false"})
class ManagedPaymentsRuntimeDatabaseTest {
    @Container
    static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("owned_managed_payments")
                    .withUsername("owned_migrator")
                    .withPassword("synthetic-private-migration")
                    .withCreateContainerCmdModifier(
                            c ->
                                    c.getHostConfig()
                                            .withPortBindings(
                                                    new PortBinding(
                                                            Ports.Binding.bindIpAndPort(
                                                                    "127.0.0.1", 0),
                                                            ExposedPort.tcp(5432))));

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword());
                var s = c.createStatement()) {
            s.execute(
                    "CREATE ROLE payment_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD"
                            + " 'owned-disposable-runtime'");
            s.execute("GRANT USAGE ON SCHEMA public TO payment_runtime");
            s.execute(
                    "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON"
                            + " TABLES TO payment_runtime");
            s.execute(
                    "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO"
                            + " payment_runtime");
        }
        r.add("managed-payments.security.issuer", () -> "https://owned-payments.invalid");
        r.add("managed-payments.security.audience", () -> "managed-payments");
        r.add(
                "managed-payments.security.jwk-set-uri",
                () -> "http://127.0.0.1:" + PEER.getAddress().getPort() + "/jwks");
        r.add("managed-payments.allow-loopback-http", () -> true);
        r.add("managed-payments.delegation.mode", () -> "validated-multi-audience");
        r.add(
                "managed-payments.peers.ledger.base-url",
                () -> "http://127.0.0.1:" + PEER.getAddress().getPort());
        r.add("managed-payments.peers.ledger.audience", () -> "ledger-funds");
        r.add("spring.r2dbc.pool.max-size", () -> 1);
        r.add("spring.r2dbc.pool.initial-size", () -> 0);
        r.add("DB_HOST", DATABASE::getHost);
        r.add("DB_PORT", () -> DATABASE.getMappedPort(5432));
        r.add("DB_NAME", DATABASE::getDatabaseName);
        r.add("DB_USERNAME", () -> "payment_runtime");
        r.add("DB_PASSWORD", () -> "owned-disposable-runtime");
        r.add("DB_SSL_MODE", () -> "disable");
        r.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        r.add("spring.r2dbc.username", () -> "payment_runtime");
        r.add("spring.r2dbc.password", () -> "owned-disposable-runtime");
        r.add("spring.flyway.url", DATABASE::getJdbcUrl);
        r.add("spring.flyway.user", DATABASE::getUsername);
        r.add("spring.flyway.password", DATABASE::getPassword);
    }

    static final java.util.Map<java.util.UUID, java.util.Map<String, Object>> RESERVATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    static volatile java.util.UUID BLOCK_OPERATION;
    static volatile java.util.concurrent.CountDownLatch
            OBSERVE_ENTERED = new java.util.concurrent.CountDownLatch(0),
            OBSERVE_RELEASED = new java.util.concurrent.CountDownLatch(0);
    static final java.util.concurrent.atomic.AtomicInteger OBSERVATIONS =
            new java.util.concurrent.atomic.AtomicInteger();
    static final com.nimbusds.jose.jwk.RSAKey KEY;
    static final com.sun.net.httpserver.HttpServer PEER;
    static final java.util.Map<java.util.UUID, String> ACCOUNT_CURRENCIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    static {
        try {
            KEY =
                    new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048)
                            .keyID("owned-payments")
                            .generate();
            PEER =
                    com.sun.net.httpserver.HttpServer.create(
                            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            PEER.createContext(
                    "/jwks",
                    e -> {
                        byte[] b =
                                new com.nimbusds.jose.jwk.JWKSet(KEY.toPublicJWK())
                                        .toString()
                                        .getBytes();
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(200, b.length);
                        e.getResponseBody().write(b);
                        e.close();
                    });
            PEER.createContext(
                    "/api/v1/tenants/owned/funds/accounts/",
                    e -> {
                        var id =
                                java.util.UUID.fromString(
                                        e.getRequestURI()
                                                .getPath()
                                                .substring(
                                                        e.getRequestURI().getPath().lastIndexOf('/')
                                                                + 1));
                        var currency = ACCOUNT_CURRENCIES.get(id);
                        var b =
                                ("{\"accountId\":\""
                                                + id
                                                + "\",\"enrollmentId\":\""
                                                + id
                                                + "\",\"contractId\":\""
                                                + id
                                                + "\",\"currency\":\""
                                                + currency
                                                + "\",\"currencyScale\":"
                                                + ("KWD".equals(currency) ? 3 : 2)
                                                + ",\"currencyPolicy\":\"owned-v1\",\"kind\":\"CUSTOMER\",\"state\":\"ACTIVE\",\"booked\":0,\"held\":0,\"available\":0,\"version\":0}")
                                        .getBytes();
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(currency == null ? 404 : 200, b.length);
                        e.getResponseBody().write(b);
                        e.close();
                    });
            PEER.createContext(
                    "/api/v1/tenants/owned/funds/reservations/reconciliation",
                    e -> {
                        OBSERVATIONS.incrementAndGet();
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        var intent = mapper.readTree(e.getRequestBody());
                        var row =
                                RESERVATIONS.get(
                                        java.util.UUID.fromString(
                                                intent.path("operationId").asText()));
                        if (intent.path("operationId")
                                .asText()
                                .equals(String.valueOf(BLOCK_OPERATION))) {
                            OBSERVE_ENTERED.countDown();
                            try {
                                if (!OBSERVE_RELEASED.await(
                                        8, java.util.concurrent.TimeUnit.SECONDS))
                                    throw new IllegalStateException(
                                            "Owned observation barrier timeout");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new java.io.IOException(interrupted);
                            }
                        }
                        int status = 200;
                        java.util.Map<String, Object> response;
                        if (row == null) {
                            status = 409;
                            response =
                                    java.util.Map.of("reason", "RECONCILIATION_DEADLINE_NOT_DUE");
                        } else if (!intent.path("ttlSeconds")
                                        .asText()
                                        .equals(row.get("ttlSeconds").toString())
                                || !intent.path("notAfterEpochMillis")
                                        .asText()
                                        .equals(row.get("notAfterEpochMillis").toString())) {
                            status = 409;
                            response =
                                    java.util.Map.of("reason", "FUNDS_OPERATION_INTENT_CONFLICT");
                        } else {
                            response = new java.util.HashMap<>();
                            response.put(
                                    "outcome",
                                    row.containsKey("absenceReceiptId")
                                            ? "ABSENT_CLOSED"
                                            : "FOUND");
                            response.put("receiptId", row.get("absenceReceiptId"));
                            response.put("observedAt", java.time.Instant.now().toString());
                            response.put("notAfterEpochMillis", row.get("notAfterEpochMillis"));
                            response.put("intent", intent);
                            response.put("reservation", row.get("reservation"));
                        }
                        byte[] b = mapper.writeValueAsBytes(response);
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(status, b.length);
                        e.getResponseBody().write(b);
                        e.close();
                    });
            PEER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @LocalServerPort int port;
    final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    java.util.UUID[] pair(String currency) {
        var a = java.util.UUID.randomUUID();
        var b = java.util.UUID.randomUUID();
        ACCOUNT_CURRENCIES.put(a, currency);
        ACCOUNT_CURRENCIES.put(b, currency);
        return new java.util.UUID[] {a, b};
    }

    String token(String tenant, java.util.UUID[] pair, String... scopes) throws Exception {
        var claims =
                new com.nimbusds.jwt.JWTClaimsSet.Builder()
                        .issuer("https://owned-payments.invalid")
                        .audience(java.util.List.of("managed-payments", "ledger-funds"))
                        .subject("owned-customer")
                        .claim("tenant_id", tenant)
                        .claim(
                                "funds_accounts",
                                java.util.Arrays.stream(pair).map(Object::toString).toList())
                        .claim("scope", String.join(" ", scopes))
                        .expirationTime(
                                java.util.Date.from(java.time.Instant.now().plusSeconds(300)))
                        .build();
        var signed =
                new com.nimbusds.jwt.SignedJWT(
                        new com.nimbusds.jose.JWSHeader.Builder(
                                        com.nimbusds.jose.JWSAlgorithm.RS256)
                                .keyID(KEY.getKeyID())
                                .build(),
                        claims);
        signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(KEY));
        return signed.serialize();
    }

    HttpResponse<String> call(String method, String path, String body, String token)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/tenants/owned/internal-payments"
                                                + path))
                        .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient()
                .send(
                        builder.method(
                                        method,
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    String create(
            java.util.UUID action,
            java.util.UUID[] pair,
            String amount,
            String currency,
            int ttl,
            Long deadline) {
        return "{\"actionId\":\""
                + action
                + "\",\"sourceAccountId\":\""
                + pair[0]
                + "\",\"destinationAccountId\":\""
                + pair[1]
                + "\",\"amount\":"
                + amount
                + ",\"currency\":\""
                + currency
                + "\",\"ttlSeconds\":"
                + ttl
                + ",\"customerNotAfterEpochMillis\":"
                + deadline
                + "}";
    }

    @Test
    void createRetainsOneIdentityDeadlineAndExactCanonicalAmount() throws Exception {
        var pair = pair("USD");
        var action = java.util.UUID.randomUUID();
        var auth = token("owned", pair, "payments:create", "payments:read", "funds:read");
        var first = call("POST", "", create(action, pair, "12.30", "USD", 30, null), auth);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var original = json.readTree(first.body());
        var replay = call("POST", "", create(action, pair, "12.300", "USD", 30, null), auth);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).path("paymentId"))
                .isEqualTo(original.path("paymentId"));
        assertThat(json.readTree(replay.body()).path("notAfterEpochMillis"))
                .isEqualTo(original.path("notAfterEpochMillis"));
        assertThat(original.path("amount").asText()).isEqualTo("12.30");
        assertThat(original.path("state").asText()).isEqualTo("RECEIVED");
        assertThat(call("GET", "/" + original.path("paymentId").asText(), null, auth).statusCode())
                .isEqualTo(200);
    }

    @Test
    void changedIntentAndHiddenDisjointActionCollisionAreExplicitConflicts() throws Exception {
        var pair = pair("USD");
        var other = pair("USD");
        var action = java.util.UUID.randomUUID();
        var auth = token("owned", pair, "payments:create", "funds:read");
        assertThat(call("POST", "", create(action, pair, "1", "USD", 30, null), auth).statusCode())
                .isEqualTo(201);
        assertThat(call("POST", "", create(action, pair, "2", "USD", 30, null), auth).statusCode())
                .isEqualTo(409);
        var hidden =
                call(
                        "POST",
                        "",
                        create(action, other, "1", "USD", 30, null),
                        token("owned", other, "payments:create", "funds:read"));
        assertThat(hidden.statusCode()).as(hidden.body()).isEqualTo(409);
    }

    @Test
    void exactCurrencyPrecisionOverflowAndDeadlineAreRejectedBeforePersistence() throws Exception {
        var pair = pair("USD");
        var auth = token("owned", pair, "payments:create", "funds:read");
        for (String amount : java.util.List.of("0", "-1", "1.001", "100000000000000000000.00"))
            assertThat(
                            call(
                                            "POST",
                                            "",
                                            create(
                                                    java.util.UUID.randomUUID(),
                                                    pair,
                                                    amount,
                                                    "USD",
                                                    30,
                                                    null),
                                            auth)
                                    .statusCode())
                    .as(amount)
                    .isEqualTo(400);
        assertThat(
                        call(
                                        "POST",
                                        "",
                                        create(
                                                java.util.UUID.randomUUID(),
                                                pair,
                                                "1",
                                                "USD",
                                                901,
                                                null),
                                        auth)
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        call(
                                        "POST",
                                        "",
                                        create(
                                                java.util.UUID.randomUUID(),
                                                pair,
                                                "1",
                                                "USD",
                                                30,
                                                1L),
                                        auth)
                                .statusCode())
                .isEqualTo(400);
        var kwd = pair("KWD");
        var result =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), kwd, "1.001", "KWD", 30, null),
                        token("owned", kwd, "payments:create", "funds:read"));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        assertThat(json.readTree(result.body()).path("amount").asText()).isEqualTo("1.001");
    }

    @Test
    void resourceGrantAndCurrentPermissionAreRequiredOnReplay() throws Exception {
        var pair = pair("USD");
        var action = java.util.UUID.randomUUID();
        String body = create(action, pair, "1", "USD", 30, null);
        assertThat(
                        call(
                                        "POST",
                                        "",
                                        body,
                                        token("owned", pair, "payments:create", "funds:read"))
                                .statusCode())
                .isEqualTo(201);
        assertThat(
                        call("POST", "", body, token("owned", pair, "payments:read", "funds:read"))
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        call(
                                        "POST",
                                        "",
                                        body,
                                        token("other", pair, "payments:create", "funds:read"))
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        call(
                                        "POST",
                                        "",
                                        body,
                                        token(
                                                "owned",
                                                new java.util.UUID[] {pair[0]},
                                                "payments:create",
                                                "funds:read"))
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void managedCreateRequiresTrustedIdentity() throws Exception {
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/api/v1/tenants/owned/internal-payments"))
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.r2dbc.core.DatabaseClient runtime;

    long adminCount(String sql) throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword());
                var st = c.createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    void admin(String sql) throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword());
                var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    void nativeFixtureContext(java.sql.Connection connection, java.util.UUID[] accounts)
            throws Exception {
        try (var statement =
                connection.prepareStatement(
                        "SELECT set_config('firefly.payment.tenant','owned',true),"
                            + "set_config('firefly.payment.accounts',?,true),"
                            + "set_config('firefly.payment.purpose','OBSERVE',true),"
                            + "set_config('firefly.payment.xid',pg_current_xact_id()::text,true),set_config('firefly.payment.until',(floor(extract(epoch"
                            + " FROM clock_timestamp())*1000)::bigint+30000)::text,true)")) {
            statement.setString(
                    1,
                    json.writeValueAsString(
                            java.util.Arrays.stream(accounts)
                                    .map(java.util.UUID::toString)
                                    .toList()));
            statement.execute();
        }
    }

    void adminManaged(String sql, java.util.UUID[] accounts) throws Exception {
        try (var connection =
                DriverManager.getConnection(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            connection.setAutoCommit(false);
            nativeFixtureContext(connection, accounts);
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        }
    }

    @Test
    void forcedRlsRuntimeRoleAndPoolReuseProtectAllManagedRelations() throws Exception {
        var pair = pair("USD");
        var auth = token("owned", pair, "payments:create", "payments:read", "funds:read");
        var created =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        for (String table :
                java.util.List.of(
                        "operation", "action", "effect", "projection", "history", "outbox")) {
            assertThat(
                            runtime.sql("SELECT count(*) AS n FROM managed_payment_" + table)
                                    .map((r, m) -> r.get("n", Long.class))
                                    .one()
                                    .block())
                    .isZero();
            assertThat(
                            adminCount(
                                    "SELECT count(*) FROM pg_class c JOIN pg_roles r ON"
                                            + " r.oid=c.relowner WHERE c.oid='managed_payment_"
                                            + table
                                            + "'::regclass AND c.relrowsecurity AND"
                                            + " c.relforcerowsecurity AND"
                                            + " r.rolname<>'payment_runtime'"))
                    .isEqualTo(1);
        }
        assertThat(
                        runtime.sql(
                                        "SELECT rolsuper OR rolbypassrls AS privileged FROM"
                                                + " pg_roles WHERE rolname=current_user")
                                .map((r, m) -> r.get("privileged", Boolean.class))
                                .one()
                                .block())
                .isFalse();
        assertThat(
                        runtime.sql(
                                        "SELECT"
                                            + " coalesce(current_setting('firefly.payment.tenant',true),'')"
                                            + " AS tenant")
                                .map((r, m) -> r.get("tenant", String.class))
                                .one()
                                .block())
                .isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                runtime.sql(
                                                "CREATE OR REPLACE FUNCTION"
                                                    + " public.managed_payment_scope(text,uuid,uuid)"
                                                    + " RETURNS boolean LANGUAGE sql AS 'SELECT"
                                                    + " true'")
                                        .fetch()
                                        .rowsUpdated()
                                        .block())
                .hasMessageContaining("permission denied");
        assertThat(
                        call(
                                        "GET",
                                        "/"
                                                + json.readTree(created.body())
                                                        .path("paymentId")
                                                        .asText(),
                                        null,
                                        token("owned", pair("USD"), "payments:read"))
                                .statusCode())
                .isEqualTo(404);
    }

    @Test
    void outboxFailureRollsBackEveryInstructionRecordAndCanBeRetried() throws Exception {
        long before = adminCount("SELECT count(*) FROM managed_payment_operation");
        var pair = pair("USD");
        String body = create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null);
        var auth = token("owned", pair, "payments:create", "funds:read");
        admin(
                "CREATE FUNCTION public.owned_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS"
                        + " $$BEGIN RAISE EXCEPTION 'OWNED_OUTBOX_FAILURE';END$$");
        admin(
                "CREATE TRIGGER owned_fail_outbox BEFORE INSERT ON managed_payment_outbox FOR EACH"
                        + " ROW EXECUTE FUNCTION public.owned_fail_outbox()");
        try {
            assertThat(call("POST", "", body, auth).statusCode()).isEqualTo(500);
            assertThat(adminCount("SELECT count(*) FROM managed_payment_operation"))
                    .isEqualTo(before);
        } finally {
            admin("DROP TRIGGER owned_fail_outbox ON managed_payment_outbox");
            admin("DROP FUNCTION public.owned_fail_outbox()");
        }
        assertThat(call("POST", "", body, auth).statusCode()).isEqualTo(201);
    }

    @Test
    void partialRuntimeOwnershipFailsClosedBeforeManagedRead() throws Exception {
        var pair = pair("USD");
        var auth = token("owned", pair, "payments:create", "payments:read", "funds:read");
        var created =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        assertThat(created.statusCode()).isEqualTo(201);
        String path = "/" + json.readTree(created.body()).path("paymentId").asText();
        admin("ALTER TABLE managed_payment_history OWNER TO payment_runtime");
        try {
            assertThat(call("GET", path, null, auth).statusCode()).isEqualTo(503);
        } finally {
            admin("ALTER TABLE managed_payment_history OWNER TO owned_migrator");
            admin(
                    "GRANT SELECT,INSERT,UPDATE,DELETE ON managed_payment_history TO"
                            + " payment_runtime");
        }
        assertThat(call("GET", path, null, auth).statusCode()).isEqualTo(200);
    }

    @Test
    void exactIntegerFieldsRejectFractionsAndOverflowInActualBootDecoder() throws Exception {
        var pair = pair("USD");
        var auth = token("owned", pair, "payments:create", "funds:read");
        for (String ttl : java.util.List.of("1.1", "1e1", "2147483648")) {
            String body =
                    create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null)
                            .replace("\"ttlSeconds\":30", "\"ttlSeconds\":" + ttl);
            assertThat(call("POST", "", body, auth).statusCode()).as(ttl).isEqualTo(400);
        }
        for (String deadline : java.util.List.of("1.1", "1e14", "9223372036854775808")) {
            String body =
                    create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null)
                            .replace(
                                    "\"customerNotAfterEpochMillis\":null",
                                    "\"customerNotAfterEpochMillis\":" + deadline);
            assertThat(call("POST", "", body, auth).statusCode()).as(deadline).isEqualTo(400);
        }
    }

    @Test
    void claimsPersistStableParticipantIdentityAndFenceAcrossReclamation() throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "funds:read",
                        "funds:reserve");
        var made =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        assertThat(made.statusCode()).as(made.body()).isEqualTo(201);
        var v = json.readTree(made.body());
        String path = "/" + v.path("paymentId").asText() + "/claims";
        var action = java.util.UUID.randomUUID();
        String body = "{\"actionId\":\"" + action + "\",\"expectedVersion\":0}";
        var claim = call("POST", path, body, auth);
        assertThat(claim.statusCode()).as(claim.body()).isEqualTo(200);
        var first = json.readTree(claim.body());
        assertThat(first.path("fence").asLong()).isEqualTo(1);
        assertThat(first.path("participantActionId").asText())
                .isEqualTo(v.path("paymentId").asText());
        var replay = call("POST", path, body, auth);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).path("leaseUntil"))
                .isEqualTo(first.path("leaseUntil"));
        assertThat(json.readTree(replay.body()).path("fence").asLong()).isEqualTo(1);
        String other =
                "{\"actionId\":\""
                        + java.util.UUID.randomUUID()
                        + "\",\"expectedVersion\":"
                        + first.path("version").asLong()
                        + "}";
        assertThat(call("POST", path, other, auth).statusCode()).isEqualTo(409);
        adminManaged(
                "UPDATE managed_payment_effect SET lease_until=clock_timestamp()-interval '1"
                        + " second' WHERE effect_id='"
                        + first.path("effectId").asText()
                        + "'",
                pair);
        var next = call("POST", path, other, auth);
        assertThat(next.statusCode()).as(next.body()).isEqualTo(200);
        var second = json.readTree(next.body());
        assertThat(second.path("fence").asLong()).isEqualTo(2);
        assertThat(second.path("effectId")).isEqualTo(first.path("effectId"));
        assertThat(second.path("participantActionId")).isEqualTo(first.path("participantActionId"));
    }

    @Test
    void claimCurrentScopeAndVersionAreMandatory() throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "funds:read",
                        "funds:reserve");
        var made =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        String path = "/" + json.readTree(made.body()).path("paymentId").asText() + "/claims";
        String body =
                "{\"actionId\":\"" + java.util.UUID.randomUUID() + "\",\"expectedVersion\":0}";
        assertThat(
                        call(
                                        "POST",
                                        path,
                                        body,
                                        token(
                                                "owned",
                                                pair,
                                                "payments:create",
                                                "payments:read",
                                                "funds:read",
                                                "funds:reserve"))
                                .statusCode())
                .isEqualTo(403);
        assertThat(call("POST", path, body.replace(":0}", ":99}"), auth).statusCode())
                .isEqualTo(409);
        assertThat(call("POST", path, body.replace(":0}", ":0.1}"), auth).statusCode())
                .isEqualTo(400);
    }

    @Test
    void unknownEffectFailureRetainsRecoverableWorkAndOldFenceCannotRecord() throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "payments:recover",
                        "funds:read",
                        "funds:reserve");
        var made =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        String root = "/" + json.readTree(made.body()).path("paymentId").asText();
        var first =
                call(
                        "POST",
                        root + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":0}",
                        auth);
        var claimed = json.readTree(first.body());
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        String failure =
                "{\"effectId\":\""
                        + claimed.path("effectId").asText()
                        + "\",\"fence\":1,\"expectedVersion\":"
                        + claimed.path("version").asLong()
                        + ",\"code\":\"TRANSPORT_UNAVAILABLE\"}";
        var failed = call("POST", root + "/failures", failure, auth);
        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(200);
        var pending = json.readTree(failed.body());
        assertThat(pending.path("state").asText()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(pending.path("pendingEffectId")).isEqualTo(claimed.path("effectId"));
        assertThat(pending.path("postingId").isNull()).isTrue();
        assertThat(call("POST", root + "/failures", failure, auth).statusCode()).isEqualTo(409);
        var queue = call("GET", "/recovery?limit=1", null, auth);
        assertThat(queue.statusCode()).as(queue.body()).isEqualTo(200);
        assertThat(json.readTree(queue.body()).path("items").size()).isLessThanOrEqualTo(1);
    }

    @Test
    void databaseRejectsBackwardFencesAndChangedParticipantIdentity() throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "funds:read",
                        "funds:reserve");
        var made =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        String root = "/" + json.readTree(made.body()).path("paymentId").asText();
        var first =
                call(
                        "POST",
                        root + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":0}",
                        auth);
        var claimed = json.readTree(first.body());
        String id = claimed.path("effectId").asText();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                adminManaged(
                                        "UPDATE managed_payment_effect SET fence=0 WHERE"
                                                + " effect_id='"
                                                + id
                                                + "'",
                                        pair))
                .hasMessageContaining("IMMUTABLE_PAYMENT_EFFECT");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                adminManaged(
                                        "UPDATE managed_payment_effect SET participant_action_id='"
                                                + java.util.UUID.randomUUID()
                                                + "' WHERE effect_id='"
                                                + id
                                                + "'",
                                        pair))
                .hasMessageContaining("IMMUTABLE_PAYMENT_EFFECT");
    }

    java.util.Map<String, Object> syntheticFound(
            com.fasterxml.jackson.databind.JsonNode payment,
            int originalTtl,
            long originalDeadline) {
        var r = new java.util.HashMap<String, Object>();
        r.put("reservationId", java.util.UUID.randomUUID().toString());
        r.put("operationId", payment.path("paymentId").asText());
        r.put("sourceAccountId", payment.path("sourceAccountId").asText());
        r.put("destinationAccountId", payment.path("destinationAccountId").asText());
        r.put("amount", new java.math.BigDecimal(payment.path("amount").asText()));
        r.put("currency", payment.path("currency").asText());
        r.put("state", "HELD");
        r.put("expiresAt", java.time.Instant.ofEpochMilli(originalDeadline).toString());
        r.put("postingId", java.util.UUID.randomUUID().toString());
        r.put("reversalId", null);
        r.put("version", 0);
        return java.util.Map.of(
                "ttlSeconds",
                originalTtl,
                "notAfterEpochMillis",
                originalDeadline,
                "reservation",
                r);
    }

    @Test
    void acknowledgmentRequiresActualFullIntentReconciliationAndCannotTrustBodyProof()
            throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "funds:read",
                        "funds:reserve",
                        "funds:reconcile");
        var made =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        var v = json.readTree(made.body());
        String root = "/" + v.path("paymentId").asText();
        var claim =
                call(
                        "POST",
                        root + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":0}",
                        auth);
        var c = json.readTree(claim.body());
        String ack =
                "{\"effectId\":\""
                        + c.path("effectId").asText()
                        + "\",\"fence\":1,\"expectedVersion\":"
                        + c.path("version").asLong()
                        + ",\"completed\":true,\"postingId\":\""
                        + java.util.UUID.randomUUID()
                        + "\"}";
        var rejectedProof = call("POST", root + "/acknowledgments", ack, auth);
        assertThat(rejectedProof.statusCode()).as(rejectedProof.body()).isEqualTo(400);
        String clean = ack.substring(0, ack.indexOf(",\"completed\"")) + "}";
        var response = call("POST", root + "/acknowledgments", clean, auth);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        assertThat(json.readTree(response.body()).path("state").asText())
                .isEqualTo("RECOVERY_REQUIRED");
        assertThat(json.readTree(response.body()).path("postingId").isNull()).isTrue();
    }

    @Test
    void acknowledgmentRejectsConflictingOriginalTtlAndDeadlineEvenWithMatchingVisiblePair()
            throws Exception {
        for (boolean wrongTtl : java.util.List.of(true, false)) {
            var pair = pair("USD");
            var auth =
                    token(
                            "owned",
                            pair,
                            "payments:create",
                            "payments:read",
                            "payments:coordinate",
                            "funds:read",
                            "funds:reserve",
                            "funds:reconcile");
            var made =
                    call(
                            "POST",
                            "",
                            create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                            auth);
            var v = json.readTree(made.body());
            String root = "/" + v.path("paymentId").asText();
            RESERVATIONS.put(
                    java.util.UUID.fromString(v.path("paymentId").asText()),
                    syntheticFound(
                            v,
                            wrongTtl ? 31 : 30,
                            v.path("notAfterEpochMillis").asLong() + (wrongTtl ? 0 : 1)));
            var claim =
                    call(
                            "POST",
                            root + "/claims",
                            "{\"actionId\":\""
                                    + java.util.UUID.randomUUID()
                                    + "\",\"expectedVersion\":0}",
                            auth);
            var c = json.readTree(claim.body());
            String ack =
                    "{\"effectId\":\""
                            + c.path("effectId").asText()
                            + "\",\"fence\":1,\"expectedVersion\":"
                            + c.path("version").asLong()
                            + "}";
            var response = call("POST", root + "/acknowledgments", ack, auth);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            assertThat(json.readTree(response.body()).path("state").asText())
                    .isEqualTo("RECOVERY_REQUIRED");
            assertThat(json.readTree(response.body()).path("reservationId").isNull()).isTrue();
        }
    }

    @Test
    void currentReconciliationScopeIsRequiredBeforeAnyObserverCall() throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "funds:read",
                        "funds:reserve");
        var made =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        String root = "/" + json.readTree(made.body()).path("paymentId").asText();
        var claim =
                call(
                        "POST",
                        root + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":0}",
                        auth);
        var c = json.readTree(claim.body());
        int before = OBSERVATIONS.get();
        var response =
                call(
                        "POST",
                        root + "/acknowledgments",
                        "{\"effectId\":\""
                                + c.path("effectId").asText()
                                + "\",\"fence\":1,\"expectedVersion\":"
                                + c.path("version").asLong()
                                + "}",
                        auth);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(OBSERVATIONS.get()).isEqualTo(before);
    }

    @Test
    void exactFullIntentFoundReceiptAdvancesReserveAcknowledgmentThroughGenuineSdkTransport()
            throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "funds:read",
                        "funds:reserve",
                        "funds:reconcile");
        var made =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        var v = json.readTree(made.body());
        String root = "/" + v.path("paymentId").asText();
        RESERVATIONS.put(
                java.util.UUID.fromString(v.path("paymentId").asText()),
                syntheticFound(v, 30, v.path("notAfterEpochMillis").asLong()));
        var claim =
                call(
                        "POST",
                        root + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":0}",
                        auth);
        var c = json.readTree(claim.body());
        var response =
                call(
                        "POST",
                        root + "/acknowledgments",
                        "{\"effectId\":\""
                                + c.path("effectId").asText()
                                + "\",\"fence\":1,\"expectedVersion\":"
                                + c.path("version").asLong()
                                + "}",
                        auth);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var found = json.readTree(response.body());
        assertThat(found.path("state").asText()).isEqualTo("RESERVED");
        assertThat(found.path("ledgerState").asText()).isEqualTo("HELD");
        assertThat(found.path("reservationId").isNull()).isFalse();
        assertThat(found.path("pendingEffectId").isNull()).isTrue();
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class CacheScope {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.fireflyframework.web.idempotency.scope.IdempotencyScopeResolver
                ownedAuthenticatedCacheScope() {
            return exchange ->
                    exchange.getPrincipal()
                            .ofType(org.springframework.security.core.Authentication.class)
                            .filter(
                                    org.springframework.security.core.Authentication
                                            ::isAuthenticated)
                            .map(org.springframework.security.core.Authentication::getPrincipal)
                            .ofType(org.springframework.security.oauth2.jwt.Jwt.class)
                            .map(
                                    jwt ->
                                            jwt.getIssuer()
                                                    + "|"
                                                    + jwt.getSubject()
                                                    + "|"
                                                    + jwt.getClaimAsString("tenant_id")
                                                    + "|"
                                                    + jwt.getClaimAsString("scope"));
        }
    }

    @Test
    void durableReplayMustRecheckCurrentResourceGrantsEvenWhenHttpCacheIsConfigured()
            throws Exception {
        var pair = pair("USD");
        var action = java.util.UUID.randomUUID();
        String body = create(action, pair, "1", "USD", 30, null);
        String key = java.util.UUID.randomUUID().toString();
        var all =
                java.util.List.of(
                        token("owned", pair, "payments:create", "funds:read"),
                        token(
                                "owned",
                                new java.util.UUID[] {pair[0]},
                                "payments:create",
                                "funds:read"));
        int index = 0;
        for (String bearer : all) {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + port
                                                    + "/api/v1/tenants/owned/internal-payments"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + bearer)
                            .header("X-Idempotency-Key", key)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                    .as(response.body())
                    .isEqualTo(index++ == 0 ? 201 : 403);
        }
    }

    @Test
    void confirmCancelDecisionsHaveOneWinnerAndRetainParticipantIdentity() throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "payments:confirm",
                        "payments:cancel",
                        "funds:read",
                        "funds:reserve",
                        "funds:reconcile",
                        "funds:commit",
                        "funds:release");
        var made =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        var v = json.readTree(made.body());
        String root = "/" + v.path("paymentId").asText();
        RESERVATIONS.put(
                java.util.UUID.fromString(v.path("paymentId").asText()),
                syntheticFound(v, 30, v.path("notAfterEpochMillis").asLong()));
        var claim =
                call(
                        "POST",
                        root + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":0}",
                        auth);
        var c = json.readTree(claim.body());
        var reserved =
                call(
                        "POST",
                        root + "/acknowledgments",
                        "{\"effectId\":\""
                                + c.path("effectId").asText()
                                + "\",\"fence\":1,\"expectedVersion\":"
                                + c.path("version").asLong()
                                + "}",
                        auth);
        assertThat(reserved.statusCode()).isEqualTo(200);
        long version = json.readTree(reserved.body()).path("version").asLong();
        var decision = java.util.UUID.randomUUID();
        String confirm =
                "{\"actionId\":\""
                        + decision
                        + "\",\"kind\":\"CONFIRM\",\"expectedVersion\":"
                        + version
                        + "}";
        String cancel =
                "{\"actionId\":\""
                        + java.util.UUID.randomUUID()
                        + "\",\"kind\":\"CANCEL\",\"expectedVersion\":"
                        + version
                        + "}";
        var accepted = call("POST", root + "/decisions", confirm, auth);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        var pending = json.readTree(accepted.body());
        assertThat(call("POST", root + "/decisions", cancel, auth).statusCode()).isEqualTo(409);
        assertThat(call("POST", root + "/decisions", confirm, auth).statusCode()).isEqualTo(202);
        var committed =
                call(
                        "POST",
                        root + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":"
                                + pending.path("version").asLong()
                                + "}",
                        auth);
        assertThat(committed.statusCode()).as(committed.body()).isEqualTo(200);
        assertThat(json.readTree(committed.body()).path("kind").asText()).isEqualTo("COMMIT");
        assertThat(json.readTree(committed.body()).path("participantActionId").asText())
                .isNotEqualTo(v.path("paymentId").asText());
    }

    @Test
    void explicitRecoveryOfReservedPaymentCreatesObservationNotAnAutomaticDecision()
            throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "payments:recover",
                        "funds:read",
                        "funds:reserve",
                        "funds:reconcile");
        var made =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null),
                        auth);
        var v = json.readTree(made.body());
        String root = "/" + v.path("paymentId").asText();
        RESERVATIONS.put(
                java.util.UUID.fromString(v.path("paymentId").asText()),
                syntheticFound(v, 30, v.path("notAfterEpochMillis").asLong()));
        var claim =
                call(
                        "POST",
                        root + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":0}",
                        auth);
        var c = json.readTree(claim.body());
        var held =
                call(
                        "POST",
                        root + "/acknowledgments",
                        "{\"effectId\":\""
                                + c.path("effectId").asText()
                                + "\",\"fence\":1,\"expectedVersion\":"
                                + c.path("version").asLong()
                                + "}",
                        auth);
        long version = json.readTree(held.body()).path("version").asLong();
        var recovered =
                call(
                        "POST",
                        root + "/recovery",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":"
                                + version
                                + "}",
                        auth);
        assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(202);
        var pending = json.readTree(recovered.body());
        assertThat(pending.path("ledgerState").asText()).isEqualTo("HELD");
        var observed =
                call(
                        "POST",
                        root + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":"
                                + pending.path("version").asLong()
                                + "}",
                        auth);
        assertThat(observed.statusCode()).as(observed.body()).isEqualTo(200);
        assertThat(json.readTree(observed.body()).path("kind").asText()).isEqualTo("OBSERVE");
    }

    record OwnedWork(
            java.util.UUID[] pair,
            String auth,
            String root,
            com.fasterxml.jackson.databind.JsonNode original) {}

    OwnedWork beginOwnedWork(int ttl) throws Exception {
        var pair = pair("USD");
        var auth =
                token(
                        "owned",
                        pair,
                        "payments:create",
                        "payments:read",
                        "payments:coordinate",
                        "payments:confirm",
                        "payments:cancel",
                        "payments:recover",
                        "funds:read",
                        "funds:reserve",
                        "funds:commit",
                        "funds:release",
                        "funds:reconcile",
                        "funds:expire");
        var response =
                call(
                        "POST",
                        "",
                        create(java.util.UUID.randomUUID(), pair, "1", "USD", ttl, null),
                        auth);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var v = json.readTree(response.body());
        RESERVATIONS.put(
                java.util.UUID.fromString(v.path("paymentId").asText()),
                syntheticFound(v, ttl, v.path("notAfterEpochMillis").asLong()));
        return new OwnedWork(pair, auth, "/" + v.path("paymentId").asText(), v);
    }

    com.fasterxml.jackson.databind.JsonNode claim(OwnedWork w, long version) throws Exception {
        var response =
                call(
                        "POST",
                        w.root() + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":"
                                + version
                                + "}",
                        w.auth());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    HttpResponse<String> acknowledge(OwnedWork w, com.fasterxml.jackson.databind.JsonNode claim)
            throws Exception {
        return call(
                "POST",
                w.root() + "/acknowledgments",
                "{\"effectId\":\""
                        + claim.path("effectId").asText()
                        + "\",\"fence\":"
                        + claim.path("fence").asLong()
                        + ",\"expectedVersion\":"
                        + claim.path("version").asLong()
                        + "}",
                w.auth());
    }

    @Test
    void exhaustedVersionCannotClaimAnEffectWithoutCapacityForItsResult() throws Exception {
        var w = beginOwnedWork(30);
        String paymentId = w.original().path("paymentId").asText();
        admin(
                "SET session_replication_role=replica; UPDATE managed_payment_operation SET"
                    + " version=9223372036854775806,payload=jsonb_set(payload,'{version}','9223372036854775806')"
                    + " WHERE payment_id='"
                        + paymentId
                        + "'; UPDATE managed_payment_projection SET"
                        + " payload=jsonb_set(payload,'{version}','9223372036854775806') WHERE"
                        + " payment_id='"
                        + paymentId
                        + "'; SET session_replication_role=origin;");
        var response =
                call(
                        "POST",
                        w.root() + "/claims",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":9223372036854775806}",
                        w.auth());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        var retained = json.readTree(call("GET", w.root(), null, w.auth()).body());
        assertThat(retained.path("version").asLong()).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(
                        adminCount(
                                "SELECT count(*) FROM managed_payment_effect WHERE fence=0 AND"
                                        + " effect_id='"
                                        + w.original().path("pendingEffectId").asText()
                                        + "'"))
                .isEqualTo(1);
    }

    @Test
    void lastRepresentableFenceCanBeAcknowledgedWithoutArithmeticOverflow() throws Exception {
        var w = beginOwnedWork(30);
        String id = w.original().path("pendingEffectId").asText();
        admin("ALTER TABLE managed_payment_effect DISABLE TRIGGER managed_effect_guard");
        try {
            adminManaged(
                    "UPDATE managed_payment_effect SET fence=9223372036854775806 WHERE effect_id='"
                            + id
                            + "'",
                    w.pair());
        } finally {
            admin("ALTER TABLE managed_payment_effect ENABLE TRIGGER managed_effect_guard");
        }
        var c = claim(w, 0);
        assertThat(c.path("fence").asLong()).isEqualTo(Long.MAX_VALUE);
        var response = acknowledge(w, c);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("state").asText()).isEqualTo("RESERVED");
    }

    @Test
    void changedCurrencyOnRetainedActionIsAConflict() throws Exception {
        var w = beginOwnedWork(30);
        String changed =
                create(
                        java.util.UUID.fromString(w.original().path("createActionId").asText()),
                        w.pair(),
                        "1",
                        "EUR",
                        30,
                        null);
        assertThat(call("POST", "", changed, w.auth()).statusCode()).isEqualTo(409);
    }

    @Test
    void acknowledgmentCannotOverwriteAReclaimedLeaseAfterRemoteObservation() throws Exception {
        var w = beginOwnedWork(30);
        var first = claim(w, 0);
        BLOCK_OPERATION = java.util.UUID.fromString(w.original().path("paymentId").asText());
        OBSERVE_ENTERED = new java.util.concurrent.CountDownLatch(1);
        OBSERVE_RELEASED = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ack = executor.submit(() -> acknowledge(w, first));
            assertThat(OBSERVE_ENTERED.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            adminManaged(
                    "UPDATE managed_payment_effect SET lease_until=clock_timestamp()-interval '1"
                            + " second' WHERE effect_id='"
                            + first.path("effectId").asText()
                            + "'",
                    w.pair());
            var second = claim(w, first.path("version").asLong());
            assertThat(second.path("fence").asLong()).isEqualTo(2);
            OBSERVE_RELEASED.countDown();
            var rejected = ack.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
            var retained = call("GET", w.root(), null, w.auth());
            assertThat(json.readTree(retained.body()).path("state").asText())
                    .isEqualTo("RESERVING");
            assertThat(json.readTree(retained.body()).path("ledgerState").isNull()).isTrue();
        } finally {
            OBSERVE_RELEASED.countDown();
            BLOCK_OPERATION = null;
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void observedCompletionAndReversalRetainTheirDistinctHistoryAndPostingLinks() throws Exception {
        var w = beginOwnedWork(30);
        var held = acknowledge(w, claim(w, 0));
        assertThat(held.statusCode()).as(held.body()).isEqualTo(200);
        var h = json.readTree(held.body());
        String action = java.util.UUID.randomUUID().toString();
        String decision =
                "{\"actionId\":\""
                        + action
                        + "\",\"kind\":\"CONFIRM\",\"expectedVersion\":"
                        + h.path("version").asLong()
                        + "}";
        var pending = call("POST", w.root() + "/decisions", decision, w.auth());
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(202);
        var committed = claim(w, json.readTree(pending.body()).path("version").asLong());
        var row =
                (java.util.Map<String, Object>)
                        RESERVATIONS
                                .get(
                                        java.util.UUID.fromString(
                                                w.original().path("paymentId").asText()))
                                .get("reservation");
        row.put("state", "COMMITTED");
        row.put("version", 1);
        var completion = acknowledge(w, committed);
        assertThat(completion.statusCode()).as(completion.body()).isEqualTo(200);
        var complete = json.readTree(completion.body());
        assertThat(complete.path("state").asText()).isEqualTo("COMPLETED");
        var recovery =
                call(
                        "POST",
                        w.root() + "/recovery",
                        "{\"actionId\":\""
                                + java.util.UUID.randomUUID()
                                + "\",\"expectedVersion\":"
                                + complete.path("version").asLong()
                                + "}",
                        w.auth());
        assertThat(recovery.statusCode()).as(recovery.body()).isEqualTo(202);
        var observation = claim(w, json.readTree(recovery.body()).path("version").asLong());
        row.put("state", "REVERSED");
        row.put("version", 2);
        String reversal = java.util.UUID.randomUUID().toString();
        row.put("reversalId", reversal);
        var reversed = acknowledge(w, observation);
        assertThat(reversed.statusCode()).as(reversed.body()).isEqualTo(200);
        var finalView = json.readTree(reversed.body());
        assertThat(finalView.path("state").asText()).isEqualTo("REVERSED");
        assertThat(finalView.path("postingId")).isEqualTo(complete.path("postingId"));
        assertThat(finalView.path("reversalId").asText()).isEqualTo(reversal);
        var history = call("GET", w.root() + "/history", null, w.auth());
        assertThat(history.statusCode()).as(history.body()).isEqualTo(200);
        assertThat(history.body()).contains("LEDGER_COMMITTED", "LEDGER_REVERSED");
    }

    @Test
    void closedWindowReceiptExpiresWithoutInventingReservationOrPosting() throws Exception {
        var w = beginOwnedWork(1);
        var c = claim(w, 0);
        long deadline = w.original().path("notAfterEpochMillis").asLong();
        String receipt = java.util.UUID.randomUUID().toString();
        RESERVATIONS.put(
                java.util.UUID.fromString(w.original().path("paymentId").asText()),
                java.util.Map.of(
                        "ttlSeconds",
                        1,
                        "notAfterEpochMillis",
                        deadline,
                        "absenceReceiptId",
                        receipt));
        long wait = deadline - System.currentTimeMillis();
        if (wait >= 0) Thread.sleep(wait + 2);
        var response = acknowledge(w, c);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var expired = json.readTree(response.body());
        assertThat(expired.path("state").asText()).isEqualTo("EXPIRED");
        assertThat(expired.path("reservationId").isNull()).isTrue();
        assertThat(expired.path("postingId").isNull()).isTrue();
        assertThat(expired.path("absenceReceiptId").asText()).isEqualTo(receipt);
    }

    @Test
    void unsupportedFinancialIntentFieldsCannotBeSilentlyDiscarded() throws Exception {
        var pair = pair("USD");
        var auth = token("owned", pair, "payments:create", "payments:read", "funds:read");
        for (String field :
                java.util.List.of(
                        "\"sourceAccountSpaceId\":\"" + java.util.UUID.randomUUID() + "\"",
                        "\"fee\":1",
                        "\"fx\":true",
                        "\"scheme\":\"SWIFT\"")) {
            String base = create(java.util.UUID.randomUUID(), pair, "1", "USD", 30, null);
            String body = base.substring(0, base.length() - 1) + "," + field + "}";
            var response = call("POST", "", body, auth);
            assertThat(response.statusCode()).as(field + " " + response.body()).isEqualTo(400);
        }
    }

    @Test
    void databaseRequiresCompleteNativeInstructionPayloadBeforeChildWrites() throws Exception {
        var w = beginOwnedWork(30);
        var malformed = w.original().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformed).remove("amount");
        java.util.UUID payment = java.util.UUID.randomUUID(), action = java.util.UUID.randomUUID();
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformed)
                .put("paymentId", payment.toString())
                .put("createActionId", action.toString());
        try (var c =
                DriverManager.getConnection(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            c.setAutoCommit(false);
            nativeFixtureContext(c, w.pair());
            try (var st =
                    c.prepareStatement(
                            "INSERT INTO"
                                + " managed_payment_operation(tenant_id,payment_id,source_account_id,destination_account_id,create_action_id,request_digest,payload,state,version)"
                                + " VALUES('owned',?,?,?,?,?,CAST(? AS jsonb),'RECEIVED',0)")) {
                st.setObject(1, payment);
                st.setObject(2, w.pair()[0]);
                st.setObject(3, w.pair()[1]);
                st.setObject(4, action);
                st.setString(5, "a".repeat(64));
                st.setString(6, json.writeValueAsString(malformed));
                org.assertj.core.api.Assertions.assertThatThrownBy(st::executeUpdate)
                        .hasMessageContaining("check constraint");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void dueReservedInstructionWithoutPendingEffectIsDiscoverableThroughRecoveryQueue()
            throws Exception {
        var w = beginOwnedWork(1);
        var response = acknowledge(w, claim(w, 0));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var reserved = json.readTree(response.body());
        assertThat(reserved.path("state").asText()).isEqualTo("RESERVED");
        assertThat(reserved.path("pendingEffectId").isNull()).isTrue();
        long expiry =
                java.time.Instant.parse(reserved.path("reservationExpiresAt").asText())
                        .toEpochMilli();
        long watchdog = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        long databaseNow;
        // The due query uses PostgreSQL's clock, not the test runner's wall clock.
        do {
            databaseNow =
                    adminCount("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint");
            if (databaseNow > expiry) break;
            Thread.sleep(10);
        } while (System.nanoTime() < watchdog);
        assertThat(databaseNow)
                .as("PostgreSQL clock must pass retained reservation expiry")
                .isGreaterThan(expiry);
        var queued = call("GET", "/recovery?limit=25", null, w.auth());
        assertThat(queued.statusCode()).as(queued.body()).isEqualTo(200);
        var items = json.readTree(queued.body()).path("items");
        boolean found = false;
        for (var item : items)
            found |= item.path("paymentId").equals(w.original().path("paymentId"));
        assertThat(found).isTrue();
    }
}
