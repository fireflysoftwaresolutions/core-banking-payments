package io.fireflysoftware.core.banking.payments.core.managed.impl;

import static org.assertj.core.api.Assertions.*;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsContracts;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.*;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;
import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;
import io.fireflysoftware.core.banking.payments.models.configuration.ManagedPaymentPersistenceConfiguration;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.impl.ManagedPaymentJournalOperationsImpl;
import io.r2dbc.pool.*;
import io.r2dbc.postgresql.*;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.data.r2dbc.core.*;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Synthetic core persistence/codec fixture only; no financial owner, HTTP, issuer or money-finality acceptance. */
class ManagedPaymentReversalPersistenceNativeIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String OBSERVER = "owned-native-observer";
    private static ConnectionPool pool;
    private static ManagedPaymentStoreImpl store;
    private static ManagedPaymentJournalRepository journal;
    private static ManagedPaymentEffectRepository effects;
    private static ManagedPaymentMonetaryProofRepository proofs;
    private static ManagedPaymentOperationRepository operations;
    private static ManagedPaymentDatabaseContextImpl context;
    private static TransactionalOperator tx;

    @BeforeAll
    static void actualMappedRepositoriesAndSameFactoryTransaction() {
        int port = Integer.parseInt(Objects.requireNonNull(System.getProperty("payments.native.port")));
        assertThat(port).isNotIn(18080,9001);
        var delegate = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder().host("127.0.0.1")
                .port(port).database("postgres").username("payments_repository_runtime")
                .password(Objects.requireNonNull(System.getenv("PAYMENTS_NATIVE_PASSWORD"))).build());
        pool = new ConnectionPool(ConnectionPoolConfiguration.builder(delegate).initialSize(0).maxSize(1)
                .maxAcquireTime(TIMEOUT).build());
        var template = new R2dbcEntityTemplate(DatabaseClient.create(pool),new DefaultReactiveDataAccessStrategy(
                PostgresDialect.INSTANCE,ManagedPaymentPersistenceConfiguration.converters()));
        var factory = new R2dbcRepositoryFactory(template);
        var nativeRepository = factory.getRepository(ManagedPaymentNativeRepository.class);
        journal = factory.getRepository(ManagedPaymentJournalRepository.class,
                RepositoryFragments.just(new ManagedPaymentJournalOperationsImpl(template)));
        operations = factory.getRepository(ManagedPaymentOperationRepository.class);
        effects = factory.getRepository(ManagedPaymentEffectRepository.class);
        proofs = factory.getRepository(ManagedPaymentMonetaryProofRepository.class);
        store = new ManagedPaymentStoreImpl(operations,factory.getRepository(ManagedPaymentActionRepository.class),effects,
                factory.getRepository(ManagedPaymentHistoryRepository.class),proofs,journal,nativeRepository);
        context = new ManagedPaymentDatabaseContextImpl(template,nativeRepository);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(pool));
    }

    @AfterAll
    static void closeOwnedPool() { if(pool!=null) pool.disposeLater().block(TIMEOUT); }

    private static final class Fixture {
        final String tenant = "positive_"+UUID.randomUUID().toString().replace("-", "").substring(0,16);
        final UUID payment=UUID.randomUUID(),create=UUID.randomUUID(),source=UUID.randomUUID(),target=UUID.randomUUID();
        final UUID reserve=UUID.randomUUID(),commit=UUID.randomUUID(),commitAction=UUID.randomUUID();
        final UUID reverse=UUID.randomUUID(),reverseAction=UUID.randomUUID(),decision=UUID.randomUUID();
        final UUID reservation=UUID.randomUUID(),posting=UUID.randomUUID(),reversal=UUID.randomUUID();
        final long recorded=System.currentTimeMillis(),deadline=recorded+300000;
        final MonetaryReceiptEnvelope commitEnvelope;
        final MonetaryReceiptEnvelope reverseEnvelope;
        View completed,admitted;

        Fixture() {
            var a=new EnrollmentSnapshot(source,UUID.randomUUID(),UUID.randomUUID(),"USD",2,"owned-policy",FundsContracts.Kind.CUSTOMER);
            var b=new EnrollmentSnapshot(target,UUID.randomUUID(),UUID.randomUUID(),"USD",2,"owned-policy",FundsContracts.Kind.CUSTOMER);
            var accepted=new AcceptedReserve(tenant,reservation,new FundsContracts.Reserve(payment,source,target,
                    new BigDecimal("1.23"),"USD",300,deadline),recorded,deadline,a,b);
            UUID receipt=UUID.randomUUID(),undo=UUID.randomUUID();
            var commitEffect=new MoneyEffect(new BigDecimal("1.23"),"USD",a,b,"123","123",source,target,
                    balances("10","1.23","8.77","0"),balances("0","0","1.23","0"));
            var reverseEffect=new MoneyEffect(new BigDecimal("1.23"),"USD",a,b,"123","123",target,source,
                    balances("8.77","0","10","0"),balances("1.23","0","0","0"));
            var actor=new Actor("https://owned-execution.invalid","owned-native-executor");
            commitEnvelope=FundsReceiptCodec.envelope(new MonetaryReceipt(receipt,receipt,tenant,reservation,payment,
                    io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.EffectKind.COMMIT,
                    commitAction,accepted,posting,null,null,recorded+1000,commitEffect,actor));
            reverseEnvelope=FundsReceiptCodec.envelope(new MonetaryReceipt(undo,undo,tenant,reservation,payment,
                    io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.EffectKind.REVERSE,
                    reverseAction,accepted,reversal,receipt,posting,recorded+2000,reverseEffect,actor));
        }

        ManagedPaymentPolicyImpl.Call caller() {
            var p=SecurityPrincipal.builder().issuer("https://owned-context.invalid").subject(OBSERVER).tenantId(tenant)
                    .scopes(Set.of("payments:create","payments:read"))
                    .claims(Map.of("exp",Instant.now().plusSeconds(120),"funds_accounts",List.of(source.toString(),target.toString()))).build();
            return new ManagedPaymentPolicyImpl.Call(p,"unused-native-context",Set.of());
        }

        <T> Mono<T> scoped(String purpose,Supplier<Mono<T>> work) {
            return Mono.defer(() -> context.establish(caller(),tenant,purpose,source,target).then(Mono.defer(work)))
                    .as(tx::transactional);
        }

        <T> T run(String purpose,Supplier<Mono<T>> work) { return scoped(purpose,work).block(TIMEOUT); }

        Mono<Void> locks() { return store.gate(tenant,payment).then(store.accountGates(tenant,source,target)); }

        void seedSyntheticCompletedCoreGraph() {
            var initial=new View(payment,create,tenant,source,target,"1.23","USD",2,"owned-policy",300,null,deadline,
                    OBSERVER,State.RECEIVED,0,null,null,null,null,null,null,reserve,null,null,null);
            run("CREATE",() -> locks().then(store.create(initial,"a".repeat(64),reserve)));
            run("MUTATE",() -> locks().then(journal.claimEffect(new ManagedPaymentJournalOperations.EffectVersion(
                            new ManagedPaymentIds.EffectId(tenant,reserve),0,0),OBSERVER,Instant.now().plusSeconds(30)))
                    .doOnNext(n -> assertThat(n).isEqualTo(1L))
                    .then(journal.acknowledgeEffect(new ManagedPaymentJournalOperations.EffectVersion(
                            new ManagedPaymentIds.EffectId(tenant,reserve),1,1))).doOnNext(n -> assertThat(n).isEqualTo(1L)));
            completed=new View(payment,create,tenant,source,target,"1.23","USD",2,"owned-policy",300,null,deadline,
                    OBSERVER,State.COMPLETED,1,reservation,posting,null,null,10L,Instant.ofEpochMilli(recorded+1100),
                    null,null,"COMMITTED",Instant.ofEpochMilli(deadline));
            var original=new ManagedPaymentEffectEntity(new ManagedPaymentIds.EffectId(tenant,commit),payment,source,target,
                    commitAction,"COMMIT","ACKNOWLEDGED",0L,0,null,null,null,null,null,null);
            run("OBSERVE",() -> locks().then(journal.insertEffect(original))
                    .then(store.save(completed,"LEDGER_COMMITTED",OBSERVER,null,commit)));
        }

        Mono<View> admission(boolean proofFirst) {
            return locks().then(store.commitOrigin(completed)).flatMap(origin -> {
                admitted=ManagedPaymentStoreImpl.evolve(completed,State.REVERSING,reverse,null);
                var row=new ManagedPaymentEffectEntity(new ManagedPaymentIds.EffectId(tenant,reverse),payment,source,target,
                        reverseAction,"REVERSE","PENDING",0L,0,null,null,null,commit,1L,reservation);
                Mono<Void> proof=Mono.defer(() -> store.retainMonetaryProof(admitted,origin.effect(),verified(commitEnvelope),funds(false),OBSERVER));
                Mono<Void> effect=Mono.defer(() -> journal.insertEffect(row).then());
                return (proofFirst?proof.then(effect):effect.then(proof))
                        .then(store.insertAction(admitted,decision,"REVERSE",ManagedPaymentCoordinatorServiceImpl.digest("REVERSE|"+payment+"|1"),OBSERVER,null))
                        .then(store.save(admitted,"REVERSE_ACCEPTED",OBSERVER,decision,reverse)).thenReturn(admitted);
            });
        }

        void claimReverse() {
            run("MUTATE",() -> locks().then(journal.claimEffect(new ManagedPaymentJournalOperations.EffectVersion(
                    new ManagedPaymentIds.EffectId(tenant,reverse),0,0),OBSERVER,Instant.now().plusSeconds(30)))
                    .doOnNext(n -> assertThat(n).isEqualTo(1L)));
        }

        Mono<View> acknowledgment() {
            return locks().then(store.effect(tenant,reverse)).flatMap(effect -> {
                var next=new View(payment,create,tenant,source,target,"1.23","USD",2,"owned-policy",300,null,deadline,
                        OBSERVER,State.REVERSED,3,reservation,posting,reversal,null,11L,Instant.ofEpochMilli(recorded+2100),
                        null,null,"REVERSED",Instant.ofEpochMilli(deadline));
                return store.retainMonetaryProof(next,effect,verified(reverseEnvelope),funds(true),OBSERVER)
                        .then(store.acknowledgeEffect(tenant,effect))
                        .then(store.save(next,"LEDGER_REVERSED",OBSERVER,null,reverse)).thenReturn(next);
            });
        }

        VerifiedFundsObservation funds(boolean reversed) {
            return new VerifiedFundsObservation(reversed?"REVERSED":"COMMITTED",reservation,posting,reversed?reversal:null,
                    null,reversed?11L:10L,Instant.ofEpochMilli(recorded+(reversed?2100:1100)),Instant.ofEpochMilli(deadline));
        }

        void noLeakedReadContext() {
            assertThat(operations.findById(new ManagedPaymentIds.OperationId(tenant,payment)).block(TIMEOUT)).isNull();
        }
    }

    private static BalanceVector balances(String a,String b,String c,String d) {
        return new BalanceVector(new BigDecimal(a),new BigDecimal(b),new BigDecimal(c),new BigDecimal(d));
    }

    private static VerifiedMonetaryProof verified(MonetaryReceiptEnvelope envelope) {
        FundsReceiptCodec.verify(envelope);
        var r=envelope.value();
        try {
            return new VerifiedMonetaryProof(MonetaryKind.valueOf(r.effectKind().name()),r.receiptId(),r.actionId(),r.reservationId(),
                    r.postingId(),r.originalCommitReceiptId(),r.originalPostingId(),envelope.canonical().encoding(),
                    envelope.canonical().canonical(),envelope.canonical().digest(),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope));
        } catch(java.io.IOException error) { throw new AssertionError(error); }
    }

    private void positiveLifecycle(boolean proofFirst) {
        var f=new Fixture();f.seedSyntheticCompletedCoreGraph();
        f.run("OBSERVE",() -> f.admission(proofFirst));f.claimReverse();
        var result=f.run("OBSERVE",f::acknowledgment);
        assertThat(result.state()).isEqualTo(State.REVERSED);
        var retained=f.run("READ",() -> proofs.findByIdTenantIdAndPaymentIdAndKind(f.tenant,f.payment,"COMMIT"));
        var reversed=f.run("READ",() -> proofs.findByIdTenantIdAndPaymentIdAndKind(f.tenant,f.payment,"REVERSE"));
        assertThat(retained.retainedVersion()).isEqualTo(2L);
        assertThat(reversed.retainedVersion()).isEqualTo(3L);
        assertThat(retained.observedReservationVersion()).isEqualTo(10L);
        assertThat(reversed.observedReservationVersion()).isEqualTo(11L);
        assertThat(reversed.captureFence()).isEqualTo(1L);
        assertThat(f.run("READ",() -> store.read(f.tenant,f.payment))).isEqualTo(result);
        assertThat(f.run("READ",() -> effects.findById(new ManagedPaymentIds.EffectId(f.tenant,f.reverse))).state()).isEqualTo("ACKNOWLEDGED");
        f.noLeakedReadContext();
    }

    @Test void actualMappedPositiveLifecycleEffectBeforeProof() { positiveLifecycle(false); }
    @Test void actualMappedPositiveLifecycleProofBeforeEffect() { positiveLifecycle(true); }

    @Test
    void ordinaryCallerRollbackLeavesCompletedGraphAndNoNewProof() {
        var f=new Fixture();f.seedSyntheticCompletedCoreGraph();
        StepVerifier.create(f.scoped("OBSERVE",() -> f.admission(false).then(Mono.error(new IllegalStateException("OWNED_CALLER_ROLLBACK")))))
                .expectErrorMessage("OWNED_CALLER_ROLLBACK").verify(TIMEOUT);
        assertThat(f.run("READ",() -> store.read(f.tenant,f.payment))).isEqualTo(f.completed);
        assertThat(f.run("READ",() -> proofs.findById(new ManagedPaymentIds.ProofId(f.tenant,f.commit)))).isNull();
        assertThat(f.run("READ",() -> effects.findById(new ManagedPaymentIds.EffectId(f.tenant,f.reverse)))).isNull();
        f.noLeakedReadContext();
    }

    @Test
    void ordinaryCallerCancellationRollsBackAcknowledgmentAndReleasesOneConnection() {
        var f=new Fixture();f.seedSyntheticCompletedCoreGraph();f.run("OBSERVE",() -> f.admission(false));f.claimReverse();
        var written=new java.util.concurrent.CountDownLatch(1);
        StepVerifier.create(f.scoped("OBSERVE",() -> f.acknowledgment().doOnNext(v -> written.countDown()).then(Mono.never())))
                .then(() -> { try { assertThat(written.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                    catch(InterruptedException error) { Thread.currentThread().interrupt();throw new AssertionError(error); } })
                .thenCancel().verify(TIMEOUT);
        assertThat(f.run("READ",() -> store.read(f.tenant,f.payment))).isEqualTo(f.admitted);
        assertThat(f.run("READ",() -> proofs.findById(new ManagedPaymentIds.ProofId(f.tenant,f.reverse)))).isNull();
        assertThat(f.run("READ",() -> effects.findById(new ManagedPaymentIds.EffectId(f.tenant,f.reverse))).state()).isEqualTo("CLAIMED");
        f.noLeakedReadContext();
    }
}
