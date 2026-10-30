package io.fireflysoftware.core.banking.payments.core.managed.impl;

import static org.assertj.core.api.Assertions.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;
import io.fireflysoftware.core.banking.payments.models.configuration.ManagedPaymentPersistenceConfiguration;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import io.r2dbc.pool.*;
import io.r2dbc.postgresql.*;
import io.r2dbc.spi.*;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.*;
import org.reactivestreams.Publisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.r2dbc.core.*;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.data.relational.core.query.*;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.impl.ManagedPaymentJournalOperationsImpl;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import java.lang.reflect.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Persistence/transaction fixture only: no remote financial owner or authentication acceptance. */
class ManagedPaymentRepositoryNativeIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final List<Execution> TRACE = new CopyOnWriteArrayList<>();
    private static final AtomicInteger CONNECTION_IDS = new AtomicInteger();
    private record Execution(int connection, String operation) {}
    private static ConnectionPool pool;
    private static R2dbcEntityTemplate template;
    private static ManagedPaymentNativeRepository nativeRepository;
    private static ManagedPaymentOperationRepository operations;
    private static ManagedPaymentActionRepository actions;
    private static ManagedPaymentEffectRepository effects;
    private static ManagedPaymentHistoryRepository histories;
    private static ManagedPaymentJournalRepository journal;
    private static ManagedPaymentDatabaseContext context;
    private static ManagedPaymentStore store;
    private static TransactionalOperator tx;
    private String tenant;
    private UUID source;
    private UUID destination;

    private static PostgresqlConnectionFactory delegate() {
        return new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host("127.0.0.1").port(Integer.parseInt(System.getProperty("payments.native.port")))
                .database("postgres").username("payments_repository_runtime")
                .password(System.getenv("PAYMENTS_NATIVE_PASSWORD")).build());
    }

    @BeforeAll
    static void initializeRealRepositories() {
        var delegate = delegate();
        ConnectionFactory traced = new ConnectionFactory() {
            public Publisher<? extends Connection> create() {
                return Flux.from(delegate.create()).map(connection -> {
                    int id = CONNECTION_IDS.incrementAndGet();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                if (method.getName().equals("createStatement")) TRACE.add(new Execution(id, (String) args[0]));
                                if (Set.of("beginTransaction", "commitTransaction", "rollbackTransaction").contains(method.getName()))
                                    TRACE.add(new Execution(id, method.getName()));
                                try { return method.invoke(connection, args); }
                                catch (InvocationTargetException error) { throw error.getCause(); }
                            });
                });
            }
            public ConnectionFactoryMetadata getMetadata() { return delegate.getMetadata(); }
        };
        pool = new ConnectionPool(ConnectionPoolConfiguration.builder(traced).initialSize(0).maxSize(1)
                .maxAcquireTime(TIMEOUT).build());
        template = new R2dbcEntityTemplate(DatabaseClient.create(pool), new DefaultReactiveDataAccessStrategy(
                PostgresDialect.INSTANCE, ManagedPaymentPersistenceConfiguration.converters()));
        var factory = new R2dbcRepositoryFactory(template);
        nativeRepository = factory.getRepository(ManagedPaymentNativeRepository.class);
        operations = factory.getRepository(ManagedPaymentOperationRepository.class);
        actions = factory.getRepository(ManagedPaymentActionRepository.class);
        effects = factory.getRepository(ManagedPaymentEffectRepository.class);
        histories = factory.getRepository(ManagedPaymentHistoryRepository.class);
        journal = factory.getRepository(ManagedPaymentJournalRepository.class,
                RepositoryFragments.just(new ManagedPaymentJournalOperationsImpl(template)));
        context = new ManagedPaymentDatabaseContext(template, nativeRepository);
        store = new ManagedPaymentStore(operations, actions, effects, histories, journal, nativeRepository);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(pool));
    }

    @AfterAll
    static void closeOwnedPool() { if (pool != null) pool.disposeLater().block(TIMEOUT); }

    @BeforeEach
    void newOwnedFixtureScope() {
        tenant = "owned_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        source = UUID.randomUUID(); destination = UUID.randomUUID(); TRACE.clear();
    }

    private ManagedPaymentPolicy.Call caller(String tenant, UUID... grants) {
        var principal = SecurityPrincipal.builder().subject("owned-native-persistence").issuer("https://owned.invalid")
                .tenantId(tenant).scopes(Set.of("payments:create", "payments:read"))
                .claims(Map.of("exp", Instant.now().plusSeconds(120), "funds_accounts",
                        Arrays.stream(grants).map(UUID::toString).toList())).build();
        return new ManagedPaymentPolicy.Call(principal, "unused-owned-fixture-credential", Set.of());
    }

    private <T> Mono<T> scope(String selected, String purpose, Supplier<Mono<T>> work, UUID... grants) {
        return Mono.defer(() -> context.establish(caller(selected, grants), selected, purpose, grants)
                .then(Mono.defer(work))).as(tx::transactional);
    }

    private <T> T scoped(String selected, String purpose, Supplier<Mono<T>> work) {
        return scope(selected, purpose, work, source, destination).block(TIMEOUT);
    }

    private View fixture(String selected, UUID payment, UUID action, UUID effect) {
        return new View(payment, action, selected, source, destination, "12.3400", "USD", 2, "USD-2026", 300,
                null, Instant.now().plusSeconds(300).toEpochMilli(), "owned-native-persistence", State.RECEIVED, 0L,
                null, null, null, null, null, null, effect, null, null, null);
    }

    private View createFixture(String selected, UUID payment, UUID action, UUID effect) {
        View value = fixture(selected, payment, action, effect);
        return scoped(selected, "CREATE", () -> store.gate(selected, payment)
                .then(store.accountGates(selected, source, destination))
                .then(store.create(value, "a".repeat(64), effect)).thenReturn(value));
    }

    private View createFixture() { return createFixture(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()); }

    private ManagedPaymentEffectEntity effect(View value) {
        return scoped(value.tenant(), "READ", () -> effects.findById(new ManagedPaymentIds.EffectId(value.tenant(), value.pendingEffectId())));
    }

    private ManagedPaymentJournalOperations.EffectVersion version(ManagedPaymentEffectEntity effect) {
        return new ManagedPaymentJournalOperations.EffectVersion(effect.id(), effect.fence(), effect.attempts());
    }

    private Mono<Long> reschedule(ManagedPaymentEffectEntity effect, long fence, int delay) {
        return nativeRepository.databaseTime().flatMap(now -> journal.rescheduleEffect(
                new ManagedPaymentJournalOperations.EffectVersion(effect.id(), fence, effect.attempts()),
                "PENDING", now.toInstant().plusSeconds(delay)));
    }

    private java.sql.Connection jdbc() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + System.getProperty("payments.native.port") + "/postgres",
                "payments_repository_runtime", System.getenv("PAYMENTS_NATIVE_PASSWORD"));
    }

    private long databaseNow(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT public.managed_payment_repository_now()")) {
            assertThat(row.next()).isTrue(); return row.getLong(1);
        }
    }

    @Test
    void sameUuidAcrossTenantsMapsAllSixRecordsWithoutJsonOrIdentityLeak() {
        UUID payment = UUID.randomUUID(), action = UUID.randomUUID(), effect = UUID.randomUUID();
        View first = createFixture(tenant, payment, action, effect);
        String other = tenant + "_other";
        View second = createFixture(other, payment, action, effect);
        for (View expected : List.of(first, second)) {
            var retained = scoped(expected.tenant(), "READ", () -> store.read(expected.tenant(), payment));
            assertThat(retained).isEqualTo(expected);
            assertThat(retained.amount()).isEqualTo("12.3400");
            assertThat(retained.ledgerVersion()).isNull();
            assertThat(scoped(expected.tenant(), "READ", () -> actions.findById(new ManagedPaymentIds.ActionId(expected.tenant(), action))).receipt()).isNull();
            assertThat(effect(expected).id()).isEqualTo(new ManagedPaymentIds.EffectId(expected.tenant(), effect));
            assertThat(scoped(expected.tenant(), "READ", () -> histories.findByIdTenantIdAndIdPaymentIdAndIdVersionGreaterThanOrderByIdVersionAsc(
                    expected.tenant(), payment, -1, PageRequest.of(0, 1)).collectList())).hasSize(1);
            var criteria = Query.query(Criteria.where("tenant_id").is(expected.tenant()).and("payment_id").is(payment));
            var projection = scoped(expected.tenant(), "READ", () -> template.selectOne(criteria, ManagedPaymentProjectionEntity.class));
            var outbox = scoped(expected.tenant(), "READ", () -> template.selectOne(criteria, ManagedPaymentOutboxEntity.class));
            assertThat(store.decode(projection.payload().value())).isEqualTo(expected);
            assertThat(outbox.id()).isEqualTo(new ManagedPaymentIds.EventId(expected.tenant(), payment, 0));
        }
        assertThat(scoped(tenant, "READ", () -> operations.findById(new ManagedPaymentIds.OperationId(other, payment)))).isNull();
        assertThat(scoped(tenant, "READ", () -> actions.findById(new ManagedPaymentIds.ActionId(other, action)))).isNull();
        assertThat(scoped(tenant, "READ", () -> effects.findById(new ManagedPaymentIds.EffectId(other, effect)))).isNull();
    }

    @Test
    void actionUniqueConflictRollsBackTheEarlierNewOperation() {
        View first = createFixture(); UUID second = UUID.randomUUID();
        assertThatThrownBy(() -> createFixture(tenant, second, first.createActionId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(scoped(tenant, "READ", () -> store.read(tenant, second))).isNull();
        assertThat(scoped(tenant, "READ", () -> actions.findById(new ManagedPaymentIds.ActionId(tenant, first.createActionId()))).paymentId())
                .isEqualTo(first.paymentId());
    }

    @Test
    void unrelatedEffectUniqueFailureRetainsSqlStateAndRollsBackAllEarlierRows() {
        View first = createFixture(); UUID second = UUID.randomUUID(), action = UUID.randomUUID();
        assertThatThrownBy(() -> createFixture(tenant, second, action, first.pendingEffectId())).satisfies(error -> {
            assertThat(error).isNotInstanceOf(ResponseStatusException.class);
            Throwable root = error; while (root.getCause() != null) root = root.getCause();
            assertThat(root).isInstanceOfSatisfying(R2dbcException.class, sql -> assertThat(sql.getSqlState()).isEqualTo("23505"));
        });
        assertThat(scoped(tenant, "READ", () -> store.read(tenant, second))).isNull();
        assertThat(scoped(tenant, "READ", () -> actions.findById(new ManagedPaymentIds.ActionId(tenant, action)))).isNull();
    }

    @Test
    void claimAndAcknowledgementExposeExactNativeCasRowcounts() {
        View value = createFixture(); var before = effect(value);
        long now = scoped(tenant, "READ", nativeRepository::now);
        assertThat(scoped(tenant, "MUTATE", () -> journal.claimEffect(version(before), "owner", Instant.ofEpochMilli(now + 30000)))).isEqualTo(1L);
        assertThat(scoped(tenant, "MUTATE", () -> journal.claimEffect(version(before), "stale", Instant.ofEpochMilli(now + 30000)))).isZero();
        var claimed = effect(value); assertThat(claimed.fence()).isEqualTo(1L); assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(scoped(tenant, "MUTATE", () -> journal.acknowledgeEffect(version(before)))).isZero();
        assertThat(scoped(tenant, "MUTATE", () -> journal.acknowledgeEffect(version(claimed)))).isEqualTo(1L);
        assertThat(scoped(tenant, "MUTATE", () -> journal.acknowledgeEffect(version(claimed)))).isZero();
        assertThat(effect(value).state()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void deferAndResetUseDatabaseTimeExactFenceAndClearOwnership() {
        View value = createFixture(); var before = effect(value);
        long now = scoped(tenant, "READ", nativeRepository::now);
        scoped(tenant, "MUTATE", () -> journal.claimEffect(version(before), "owner", Instant.ofEpochMilli(now + 30000)));
        var claimed = effect(value);
        long lower = scoped(tenant, "READ", nativeRepository::now);
        assertThat(scoped(tenant, "MUTATE", () -> reschedule(claimed, claimed.fence(), 2))).isEqualTo(1L);
        long upper = scoped(tenant, "READ", nativeRepository::now);
        var deferred = effect(value);
        assertThat(deferred.nextAttemptAt().toInstant().toEpochMilli()).isBetween(lower + 2000, upper + 2000);
        assertThat(deferred.ownerSubject()).isNull(); assertThat(deferred.leaseUntil()).isNull();
        assertThat(scoped(tenant, "MUTATE", () -> reschedule(claimed, 0, 30))).isZero();
        assertThat(effect(value).nextAttemptAt()).isEqualTo(deferred.nextAttemptAt());
        lower = scoped(tenant, "READ", nativeRepository::now);
        assertThat(scoped(tenant, "MUTATE", () -> reschedule(claimed, claimed.fence(), 0))).isEqualTo(1L);
        upper = scoped(tenant, "READ", nativeRepository::now);
        assertThat(effect(value).nextAttemptAt().toInstant().toEpochMilli()).isBetween(lower, upper);
        assertThat(scoped(tenant, "MUTATE", () -> reschedule(claimed, 0, 0))).isZero();
    }

    @Test
    void nativeRecoveryQueueIsBoundedOrderedAndHonorsLiveLeaseAndBackoff() {
        View a = createFixture(tenant, new UUID(0, 1), UUID.randomUUID(), UUID.randomUUID());
        View b = createFixture(tenant, new UUID(0, 2), UUID.randomUUID(), UUID.randomUUID());
        View c = createFixture(tenant, new UUID(0, 3), UUID.randomUUID(), UUID.randomUUID());
        var first = scoped(tenant, "READ", () -> nativeRepository.recoveryQueue(tenant, null, 2).collectList());
        assertThat(first.stream().map(row -> row.id().paymentId())).containsExactly(a.paymentId(), b.paymentId());
        assertThat(scoped(tenant, "READ", () -> nativeRepository.recoveryQueue(tenant, b.paymentId(), 2).collectList())
                .stream().map(row -> row.id().paymentId())).containsExactly(c.paymentId());
        var pending = effect(a); long now = scoped(tenant, "READ", nativeRepository::now);
        scoped(tenant, "MUTATE", () -> journal.claimEffect(version(pending), "owner", Instant.ofEpochMilli(now + 30000)));
        var pendingB = effect(b);
        scoped(tenant, "MUTATE", () -> reschedule(pendingB, 0, 30));
        assertThat(scoped(tenant, "READ", () -> nativeRepository.recoveryQueue(tenant, null, 10).collectList())
                .stream().map(row -> row.id().paymentId())).containsExactly(c.paymentId());
        assertThat(scoped(tenant, "READ", () -> nativeRepository.recoveryQueue(tenant + "_other", null, 10).collectList())).isEmpty();
    }

    @Test
    void databaseClockIsSampledAfterTheActualPaymentLockWait() throws Exception {
        UUID payment = UUID.randomUUID(); String key = "managed-payment:" + tenant + ":" + payment;
        try (var holder = jdbc()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) { lock.setString(1, key); lock.execute(); }
            long acquired = databaseNow(holder);
            var waiting = scope(tenant, "MUTATE", () -> store.gate(tenant, payment).then(nativeRepository.now()), source, destination)
                    .subscribeOn(Schedulers.boundedElastic()).toFuture();
            boolean blocked = false;
            for (int i = 0; i < 100; i++) {
                try (var statement = holder.createStatement(); var row = statement.executeQuery("SELECT count(*) FROM pg_catalog.pg_locks WHERE locktype='advisory' AND NOT granted")) {
                    row.next(); if (row.getInt(1) > 0) { blocked = true; break; }
                }
                Thread.sleep(10);
            }
            assertThat(blocked).isTrue(); Thread.sleep(80);
            long released = databaseNow(holder); holder.commit();
            long observed = waiting.get(10, TimeUnit.SECONDS);
            assertThat(observed).isGreaterThanOrEqualTo(released).isGreaterThan(acquired + 40);
        }
    }

    private Map<String, String> residualContext() {
        return template.getDatabaseClient().sql("SELECT current_setting('firefly.payment.tenant',true) AS tenant, current_setting('firefly.payment.accounts',true) AS accounts, current_setting('firefly.payment.purpose',true) AS purpose, current_setting('firefly.payment.xid',true) AS xid, current_setting('firefly.payment.until',true) AS until")
                .map((row, metadata) -> {
                    Map<String, String> result = new LinkedHashMap<>();
                    for (String key : List.of("tenant", "accounts", "purpose", "xid", "until")) {
                        String value = row.get(key, String.class); result.put(key, value == null ? "" : value);
                    }
                    return result;
                }).one().block(TIMEOUT);
    }

    @Test
    void completeMappedJournalUsesOneConnectionAndCommitClearsAllContext() {
        View value = createFixture(); TRACE.clear();
        scoped(tenant, "MUTATE", () -> store.gate(tenant, value.paymentId())
                .then(store.accountGates(tenant, source, destination))
                .then(nativeRepository.now()).flatMap(now -> store.effect(tenant, value.pendingEffectId())
                        .flatMap(pending -> store.claimEffect(tenant, pending, "owned-native-persistence", Instant.ofEpochMilli(now + 30000))))
                .then(store.save(ManagedPaymentStore.evolve(value, State.RESERVING, value.pendingEffectId(), null),
                        "EFFECT_CLAIMED", "owned-native-persistence", value.createActionId(), value.pendingEffectId())));
        assertThat(TRACE.stream().map(Execution::connection).distinct()).hasSize(1);
        assertThat(TRACE.stream().map(Execution::operation)).contains("beginTransaction", "commitTransaction");
        assertThat(residualContext().values()).containsOnly("");
        assertThat(scoped(tenant, "READ", () -> store.read(tenant, value.paymentId())).version()).isEqualTo(1L);
    }

    @Test
    void rollbackRemovesAllMappedRowsAndReusesTheCleanConnection() {
        View value = fixture(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()); TRACE.clear();
        StepVerifier.create(scope(tenant, "CREATE", () -> store.gate(tenant, value.paymentId())
                .then(store.accountGates(tenant, source, destination)).then(store.create(value, "a".repeat(64), value.pendingEffectId()))
                .then(Mono.error(new IllegalStateException("owned rollback"))), source, destination))
                .expectErrorMessage("owned rollback").verify();
        assertThat(TRACE.stream().map(Execution::operation)).contains("rollbackTransaction");
        assertThat(residualContext().values()).containsOnly("");
        assertThat(TRACE.stream().map(Execution::connection).distinct()).hasSize(1);
        assertThat(scoped(tenant, "READ", () -> store.read(tenant, value.paymentId()))).isNull();
        assertThat(scoped(tenant, "READ", () -> actions.findById(new ManagedPaymentIds.ActionId(tenant, value.createActionId())))).isNull();
    }

    @Test
    void cancellationRollsBackMappedWritesAndCannotLeakContextOnTheSinglePooledConnection() {
        View value = fixture(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Sinks.One<Void> inserted = Sinks.one(); TRACE.clear();
        var subscription = scope(tenant, "CREATE", () -> store.gate(tenant, value.paymentId())
                .then(store.accountGates(tenant, source, destination)).then(store.create(value, "a".repeat(64), value.pendingEffectId()))
                .doOnSuccess(ignored -> inserted.tryEmitEmpty()).then(Mono.never()), source, destination)
                .subscribe(ignored -> {}, error -> inserted.tryEmitError(error));
        inserted.asMono().block(TIMEOUT); subscription.dispose();
        assertThat(residualContext().values()).containsOnly("");
        assertThat(TRACE.stream().map(Execution::operation)).contains("rollbackTransaction");
        assertThat(TRACE.stream().map(Execution::connection).distinct()).hasSize(1);
        assertThat(scoped(tenant, "READ", () -> store.read(tenant, value.paymentId()))).isNull();
    }

    @Test
    void wrongLiveTransactionFactoryIsRejectedBeforeRepositorySql() {
        var wrong = TransactionalOperator.create(new R2dbcTransactionManager(delegate())); TRACE.clear();
        StepVerifier.create(context.establish(caller(tenant, source, destination), tenant, "READ", source, destination).as(wrong::transactional))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                        response -> assertThat(response.getStatusCode().value()).isEqualTo(503))).verify();
        assertThat(TRACE).isEmpty();
    }

    @Test
    void ordinaryPairRlsAndReadPurposeCannotBeBroadenedByMappedOperations() {
        View value = createFixture();
        assertThat(scope(tenant, "READ", () -> store.read(tenant, value.paymentId()), source).block(TIMEOUT)).isNull();
        View forbidden = fixture(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> scoped(tenant, "READ", () -> store.create(forbidden, "a".repeat(64), forbidden.pendingEffectId())))
                .satisfies(error -> {
                    Throwable root = error; while (root.getCause() != null) root = root.getCause();
                    assertThat(root).isInstanceOfSatisfying(R2dbcException.class, sql -> {
                        assertThat(sql.getSqlState()).isEqualTo("P0001");
                        assertThat(sql.getMessage()).isEqualTo("CURRENT_ACCOUNT_GATE_CONTEXT_REQUIRED");
                    });
                });
        var projection = new ManagedPaymentProjectionEntity(new ManagedPaymentIds.OperationId(tenant, forbidden.paymentId()),
                source, destination, new PaymentJson(store.encode(forbidden)));
        assertThatThrownBy(() -> scoped(tenant, "READ", () -> journal.insertProjection(projection)))
                .satisfies(error -> {
                    Throwable root = error; while (root.getCause() != null) root = root.getCause();
                    assertThat(root).isInstanceOfSatisfying(R2dbcException.class, sql -> assertThat(sql.getSqlState()).isEqualTo("42501"));
                });
        assertThat(scoped(tenant, "READ", () -> store.read(tenant, forbidden.paymentId()))).isNull();
    }
}
