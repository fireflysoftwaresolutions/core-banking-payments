package io.fireflysoftware.core.banking.payments.core.managed.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort;
import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsContracts;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.EnrollmentSnapshot;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.AcceptedReserve;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.BalanceVector;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.MoneyEffect;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.MonetaryReceipt;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.Actor;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ManagedPaymentReversalTest {
    private static UUID id(int n) { return new UUID(0, n); }
    private static DecisionKind reverseKind() {
        assertThatCode(() -> DecisionKind.valueOf("REVERSE")).doesNotThrowAnyException();
        return DecisionKind.valueOf("REVERSE");
    }
    private static View completed() {
        return new View(id(1), id(100), "owned", id(2), id(3), "1.23", "USD", 2, "policy", 300,
                null, 1700000300000L, "initiator", State.COMPLETED, 100, id(4), id(5), null,
                null, 1L, Instant.parse("2023-11-14T22:13:21Z"), null, null, "COMMITTED",
                Instant.ofEpochMilli(1700000300000L));
    }
    private static ManagedPaymentPolicy.Call call(Set<String> scopes) {
        var p = SecurityPrincipal.builder().subject("current-reverse-reader").issuer("https://unit.invalid")
                .tenantId("owned").scopes(scopes).claims(Map.of("exp", Instant.now().plusSeconds(60),
                        "funds_accounts", List.of(id(2).toString(), id(3).toString()))).build();
        return new ManagedPaymentPolicy.Call(p, "unit-context-only", Set.of("payments-core", "ledger-funds"));
    }
    private static Set<String> allowed() {
        return Set.of("payments:reverse", "funds:reverse", "payments:coordinate", "payments:recover",
                "funds:read", "funds:reconcile");
    }
    private static VerifiedMonetaryProof proof(boolean reverse) {
        var reserve = new FundsContracts.Reserve(id(1),id(2),id(3),new BigDecimal("1.23"),"USD",300,1700000300000L);
        var source = new EnrollmentSnapshot(id(2),id(12),id(22),"USD",2,"policy",FundsContracts.Kind.CUSTOMER);
        var target = new EnrollmentSnapshot(id(3),id(13),id(23),"USD",2,"policy",FundsContracts.Kind.CUSTOMER);
        var accepted = new AcceptedReserve("owned",id(4),reserve,1700000000000L,1700000300000L,source,target);
        var e = new MoneyEffect(new BigDecimal("1.23"),"USD",source,target,"123","123",
                reverse?id(3):id(2),reverse?id(2):id(3),
                reverse?balance("8.77","0","10","0"):balance("10","1.23","8.77","0"),
                reverse?balance("1.23","0","0","0"):balance("0","0","1.23","0"));
        var receipt = new MonetaryReceipt(reverse?id(8):id(6),reverse?id(8):id(6),"owned",id(4),id(1),
                reverse?io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.EffectKind.REVERSE
                        :io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.EffectKind.COMMIT,reverse?id(23):id(21),accepted,
                reverse?id(10):id(5),reverse?id(6):null,reverse?id(5):null,
                reverse?1700000002000L:1700000001000L,e,new Actor("https://execution.invalid","original-executor"));
        var envelope = FundsReceiptCodec.envelope(receipt);
        String json;
        try { json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope); }
        catch(Exception error) { throw new AssertionError(error); }
        return new VerifiedMonetaryProof(reverse?MonetaryKind.REVERSE:MonetaryKind.COMMIT,receipt.receiptId(),
                receipt.actionId(),id(4),receipt.postingId(),receipt.originalCommitReceiptId(),receipt.originalPostingId(),
                envelope.canonical().encoding(),envelope.canonical().canonical(),envelope.canonical().digest(),json);
    }
    private static BalanceVector balance(String a,String b,String c,String d) {
        return new BalanceVector(new BigDecimal(a),new BigDecimal(b),new BigDecimal(c),new BigDecimal(d));
    }
    private static final class SourceTransactionManager extends AbstractReactiveTransactionManager {
        protected Object doGetTransaction(TransactionSynchronizationManager manager) { return new Object(); }
        protected Mono<Void> doBegin(TransactionSynchronizationManager m,Object t,TransactionDefinition d) { return Mono.empty(); }
        protected Mono<Void> doCommit(TransactionSynchronizationManager m,GenericReactiveTransaction t) { return Mono.empty(); }
        protected Mono<Void> doRollback(TransactionSynchronizationManager m,GenericReactiveTransaction t) { return Mono.empty(); }
    }
    private static final class Fixture {
        final AtomicReference<View> current = new AtomicReference<>(completed());
        final AtomicInteger peerReads = new AtomicInteger();
        final AtomicInteger proofWrites = new AtomicInteger();
        final AtomicInteger acknowledgments = new AtomicInteger();
        final Map<UUID,ManagedPaymentMonetaryProofEntity> proofRows = new HashMap<>();
        ManagedPaymentStore.EffectRow claimed;
        boolean reverseUnavailable, wrongReverseParticipant;
        final Map<UUID,ManagedPaymentStore.RetainedAction> actions = new HashMap<>();
        final List<String> purposes = new ArrayList<>();
        boolean fenced, changeVersionDuringPeerRead;
        final ManagedPaymentStore store;
        final LedgerFundsObservationPort ledger = mock(LedgerFundsObservationPort.class);
        final ManagedPaymentCoordinatorServiceImpl service;
        final ManagedPaymentTransactions transactions;
        Fixture() {
            store = mock(ManagedPaymentStore.class, invocation -> {
                String name = invocation.getMethod().getName();
                return switch (name) {
                    case "gate" -> Mono.empty();
                    case "decode" -> new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                            (String)invocation.getArgument(0),(Class<?>)invocation.getArgument(1));
                    case "accountGates" -> fenced ? Mono.error(ManagedPaymentPolicy.fail(409,"PAYMENT_ACCOUNT_QUIESCED")) : Mono.empty();
                    case "read" -> Mono.just(current.get());
                    case "now" -> Mono.just(System.currentTimeMillis());
                    case "effect" -> Mono.just(claimed);
                    case "monetaryProof" -> Mono.justOrEmpty(proofRows.get((UUID)invocation.getArgument(1)));
                    case "acknowledgeEffect" -> { acknowledgments.incrementAndGet(); yield Mono.empty(); }
                    case "deferEffect" -> Mono.empty();
                    case "action" -> Mono.justOrEmpty(actions.get((UUID)invocation.getArgument(1)));
                    case "commitOrigin" -> {
                        var e = effect(false);
                        var h = new HistoryEvent(68, "LEDGER_COMMITTED", "original-executor", Instant.EPOCH,
                                null, id(20), State.COMPLETED, id(5), null);
                        var type = Class.forName(ManagedPaymentStore.class.getName()+"$CommitOrigin");
                        yield Mono.just(type.getDeclaredConstructor(ManagedPaymentStore.EffectRow.class,
                                HistoryEvent.class).newInstance(e,h));
                    }
                    case "insertReverseEffect" -> Mono.just(effect(true));
                    case "retainMonetaryProof" -> {
                        proofWrites.incrementAndGet();
                        View v = invocation.getArgument(0); ManagedPaymentStore.EffectRow effect = invocation.getArgument(1);
                        VerifiedMonetaryProof proof = invocation.getArgument(2); VerifiedFundsObservation funds = invocation.getArgument(3);
                        proofRows.put(effect.id(),new ManagedPaymentMonetaryProofEntity(new ManagedPaymentIds.ProofId("owned",effect.id()),
                                v.paymentId(),v.sourceAccountId(),v.destinationAccountId(),effect.participant(),effect.kind().name(),
                                proof.receiptId(),v.version(),effect.fence(),funds.ledgerVersion(),funds.observedAt().atOffset(java.time.ZoneOffset.UTC),
                                invocation.getArgument(4),new PaymentJson(proof.envelopeJson()),null));
                        yield Mono.empty();
                    }
                    case "insertAction" -> {
                        View v = invocation.getArgument(0); UUID action = invocation.getArgument(1);
                        actions.put(action,new ManagedPaymentStore.RetainedAction(v.paymentId(),
                                invocation.getArgument(2),invocation.getArgument(3),invocation.getArgument(4),null));
                        yield Mono.empty();
                    }
                    case "save" -> { current.set(invocation.getArgument(0)); yield Mono.empty(); }
                    default -> throw new AssertionError("Unexpected source store boundary: "+name);
                };
            });
            var context = mock(ManagedPaymentDatabaseContext.class);
            when(context.establish(any(),anyString(),anyString(),anyString(),any(UUID[].class)))
                    .thenAnswer(invocation -> { purposes.add(invocation.getArgument(2)); return Mono.empty(); });
            transactions = new ManagedPaymentTransactions(store,context,ledger,new SourceTransactionManager());
            service = new ManagedPaymentCoordinatorServiceImpl(transactions,30);
            when(ledger.observeMonetary(anyString(),any(),any())).thenAnswer(invocation ->
                    TransactionSynchronizationManager.forCurrentTransaction()
                            .map(TransactionSynchronizationManager::isActualTransactionActive)
                            .onErrorReturn(org.springframework.transaction.NoTransactionException.class,false)
                            .flatMap(active -> {
                                assertThat(active).as("peer I/O outside source transaction").isFalse();
                                peerReads.incrementAndGet();
                                var expected = (MonetaryExpectation)invocation.getArgument(2);
                                if(changeVersionDuringPeerRead) current.set(ManagedPaymentStore.evolve(current.get(),current.get().state(),current.get().pendingEffectId(),null));
                                if(expected.kind()==MonetaryKind.REVERSE && reverseUnavailable)
                                    return Mono.error(new ObservationUnavailable(FailureCode.OBSERVATION_UNAVAILABLE));
                                var reverse = expected.kind()==MonetaryKind.REVERSE ? proof(true) : null;
                                if(reverse!=null && wrongReverseParticipant)
                                    reverse = new VerifiedMonetaryProof(reverse.kind(),reverse.receiptId(),id(99),reverse.reservationId(),
                                            reverse.postingId(),reverse.originalCommitReceiptId(),reverse.originalPostingId(),
                                            reverse.encoding(),reverse.canonical(),reverse.digest(),reverse.envelopeJson());
                                return Mono.just(new VerifiedMonetaryObservation(funds(reverse!=null),proof(false),reverse));
                            }));
            when(ledger.observe(anyString(),any())).thenAnswer(invocation -> Mono.just(funds(!reverseUnavailable)));

        }
        static ManagedPaymentStore.EffectRow effect(boolean reverse) throws Exception {
            var kind = reverse ? EffectKind.valueOf("REVERSE") : EffectKind.COMMIT;
            var type = ManagedPaymentStore.EffectRow.class;
            Object[] basic = {reverse?id(22):id(20),reverse?id(23):id(21),kind,
                    reverse?"PENDING":"ACKNOWLEDGED",reverse?0L:2L,reverse?0:2,null,null};
            var values = new ArrayList<>(Arrays.asList(basic));
            if(type.getRecordComponents().length>8) values.addAll(Arrays.asList(reverse?id(20):null,reverse?68L:null,reverse?id(4):null));
            return (ManagedPaymentStore.EffectRow)type.getDeclaredConstructor(Arrays.stream(type.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(values.toArray());
        }
        static VerifiedFundsObservation funds(boolean reversed) {
            return new VerifiedFundsObservation(reversed?"REVERSED":"COMMITTED",id(4),id(5),reversed?id(10):null,
                    null,reversed?3L:2L,Instant.parse("2026-10-24T01:02:03Z"),Instant.ofEpochMilli(1700000300000L));
        }
        void claimSelectedReverse() throws Exception {
            current.set(ManagedPaymentStore.evolve(current.get(),State.valueOf("REVERSING"),id(22),null));
            claimed = new ManagedPaymentStore.EffectRow(id(22),id(23),EffectKind.valueOf("REVERSE"),"CLAIMED",1L,1,
                    "current-reverse-reader",Instant.now().plusSeconds(30),id(20),68L,id(4));
        }
        Mono<View> acknowledge(ManagedPaymentPolicy.Call call) {
            return new ManagedPaymentObservationServiceImpl(transactions,service,ledger)
                    .acknowledge("owned",id(1),new Acknowledge(id(22),1L,current.get().version()))
                    .contextWrite(context -> ManagedPaymentPolicy.with(context,call));
        }
        Mono<View> reverse(ManagedPaymentPolicy.Call call) {
            return service.decide("owned",id(1),new Decision(id(24),reverseKind(),100L))
                    .contextWrite(context -> ManagedPaymentPolicy.with(context,call));
        }
    }

    @Test
    void completedPaymentAdmitsLinkedPendingReverseAfterExternalProofRead() {
        var fixture = new Fixture();
        var result = fixture.reverse(call(allowed())).block();
        assertThat(result.state().name()).isEqualTo("REVERSING");
        assertThat(result.pendingEffectId()).isEqualTo(id(22));
        assertThat(result.version()).isEqualTo(101);
        assertThat(result.ledgerVersion()).isEqualTo(1);
        assertThat(result.notAfterEpochMillis()).isEqualTo(completed().notAfterEpochMillis());
        assertThat(fixture.purposes).containsExactly("READ","OBSERVE");
        assertThat(fixture.peerReads.get()).isEqualTo(1);
        assertThat(fixture.proofWrites.get()).isEqualTo(1);
    }

    @Test
    void originalDecisionReplayKeepsSelectedWorkWithoutAnotherPeerOrProof() {
        var fixture = new Fixture(); var actor = call(allowed());
        var first = fixture.reverse(actor).block(); var replay = fixture.reverse(actor).block();
        assertThat(replay).isEqualTo(first);
        assertThat(fixture.peerReads.get()).isEqualTo(1);
        assertThat(fixture.proofWrites.get()).isEqualTo(1);
    }

    @Test
    void currentGenericRecoveryAuthorityCannotAdmitReverse() {
        var fixture = new Fixture();
        StepVerifier.create(fixture.reverse(call(Set.of("payments:recover","payments:coordinate",
                        "funds:reverse","funds:read","funds:reconcile"))))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403))).verify();
        assertThat(fixture.peerReads.get()).isZero(); assertThat(fixture.actions).isEmpty();
    }

    @Test
    void changedCoreJournalVersionAfterPeerReadRejectsAdmission() {
        var fixture = new Fixture(); fixture.changeVersionDuringPeerRead = true;
        StepVerifier.create(fixture.reverse(call(allowed())))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409))).verify();
        assertThat(fixture.proofWrites.get()).isZero(); assertThat(fixture.actions).isEmpty();
    }

    @Test
    void existingEndpointFenceRejectsNewPendingReverseWithoutJournalWrites() {
        var fixture = new Fixture(); fixture.fenced = true;
        StepVerifier.create(fixture.reverse(call(allowed())))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("PAYMENT_ACCOUNT_QUIESCED"))).verify();
        assertThat(fixture.proofWrites.get()).isZero(); assertThat(fixture.actions).isEmpty();
    }
    @Test
    void selectedReceiptAcknowledgmentRetainsOriginalProofAndOwnerVersion() throws Exception {
        var fixture = new Fixture(); var actor = call(allowed()); fixture.reverse(actor).block();
        var original = fixture.proofRows.get(id(20)); fixture.claimSelectedReverse();
        var result = fixture.acknowledge(actor).block();
        assertThat(result.state()).isEqualTo(State.REVERSED);
        assertThat(result.pendingEffectId()).isNull();
        assertThat(result.version()).isEqualTo(103);
        assertThat(result.ledgerVersion()).isEqualTo(3);
        assertThat(fixture.proofWrites.get()).isEqualTo(2);
        assertThat(fixture.acknowledgments.get()).isEqualTo(1);
        assertThat(fixture.proofRows.get(id(20))).isSameAs(original);
        assertThat(fixture.proofRows.get(id(22)).participantActionId()).isEqualTo(id(23));
    }

    @Test
    void committedOnlyObservationCannotAcknowledgePendingReverse() throws Exception {
        var fixture = new Fixture(); var actor = call(allowed()); fixture.reverse(actor).block();
        fixture.claimSelectedReverse(); fixture.reverseUnavailable=true;
        var result = fixture.acknowledge(actor).block();
        assertThat(result.state()).isEqualTo(State.RECOVERY_REQUIRED);
        assertThat(result.pendingEffectId()).isEqualTo(id(22));
        assertThat(fixture.acknowledgments.get()).isZero();
        assertThat(fixture.proofWrites.get()).isEqualTo(1);
    }

    @Test
    void anotherSelectedReverseReceiptKeepsPendingWork() throws Exception {
        var fixture = new Fixture(); var actor = call(allowed()); fixture.reverse(actor).block();
        fixture.claimSelectedReverse(); fixture.wrongReverseParticipant=true;
        var result = fixture.acknowledge(actor).block();
        assertThat(result.state()).isEqualTo(State.RECOVERY_REQUIRED);
        assertThat(result.pendingEffectId()).isEqualTo(id(22));
        assertThat(fixture.acknowledgments.get()).isZero();
        assertThat(fixture.proofWrites.get()).isEqualTo(1);
    }

    private void observedReversedWithoutSelectedReceiptKeepsRecovery(EffectKind kind) {
        var fixture = new Fixture();
        View prior = completed();
        fixture.current.set(new View(prior.paymentId(),prior.createActionId(),prior.tenant(),prior.sourceAccountId(),
                prior.destinationAccountId(),prior.amount(),prior.currency(),prior.currencyScale(),prior.currencyPolicy(),
                prior.ttlSeconds(),prior.customerNotAfterEpochMillis(),prior.notAfterEpochMillis(),prior.initiator(),
                kind==EffectKind.COMMIT?State.COMMITTING:State.RECOVERY_REQUIRED,100,prior.reservationId(),
                kind==EffectKind.COMMIT?null:prior.postingId(),null,null,1L,prior.observedAt(),id(22),null,
                kind==EffectKind.COMMIT?"HELD":"COMMITTED",prior.reservationExpiresAt()));
        fixture.claimed = new ManagedPaymentStore.EffectRow(id(22),id(23),kind,"CLAIMED",1L,1,
                "current-reverse-reader",Instant.now().plusSeconds(30));
        var scopes = new HashSet<>(allowed()); scopes.add("funds:commit");
        var before = fixture.current.get();
        var result = fixture.acknowledge(call(scopes)).block();
        assertThat(result.state()).isEqualTo(State.RECOVERY_REQUIRED);
        assertThat(result.recoveryCode()).isEqualTo(FailureCode.INCONSISTENT_OBSERVATION.name());
        assertThat(result.pendingEffectId()).isEqualTo(id(22));
        assertThat(result.ledgerVersion()).isEqualTo(before.ledgerVersion());
        assertThat(result.postingId()).isEqualTo(before.postingId());
        assertThat(result.reversalId()).isNull();
        assertThat(fixture.acknowledgments.get()).isZero();
        assertThat(fixture.proofWrites.get()).isZero();
        assertThat(fixture.proofRows).isEmpty();
        verify(fixture.store).deferEffect("owned",fixture.claimed);
        verify(fixture.ledger,never()).observeMonetary(anyString(),any(),any());
    }

    @Test
    void genericCommitObservationCannotInferSelectedReversalFromReservationState() {
        observedReversedWithoutSelectedReceiptKeepsRecovery(EffectKind.COMMIT);
    }

    @Test
    void genericRecoveryObservationCannotInferSelectedReversalFromReservationState() {
        observedReversedWithoutSelectedReceiptKeepsRecovery(EffectKind.OBSERVE);
    }

    @Test
    void replayRechecksCurrentReverseAuthorityWithoutAnotherPeerRead() {
        var fixture = new Fixture(); fixture.reverse(call(allowed())).block();
        var noReverse = new HashSet<>(allowed()); noReverse.remove("payments:reverse");
        StepVerifier.create(fixture.reverse(call(noReverse))).expectErrorSatisfies(error ->
                assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                        value -> assertThat(value.getStatusCode().value()).isEqualTo(403))).verify();
        assertThat(fixture.peerReads.get()).isEqualTo(1);
        assertThat(fixture.proofWrites.get()).isEqualTo(1);
    }

    @Test
    void reverseClaimCannotUseGenericRecoveryOrMissingFundsAuthority() throws Exception {
        var effect = Fixture.effect(true);
        for (String withdrawn : List.of("payments:reverse","funds:reverse")) {
            var scopes = new HashSet<>(allowed()); scopes.remove(withdrawn);
            assertThatThrownBy(() -> ManagedPaymentCoordinatorServiceImpl.effectAuthority(
                    call(scopes),"owned",effect,completed()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            value -> assertThat(value.getStatusCode().value()).isEqualTo(403));
        }
    }

    @Test
    void acknowledgmentRechecksReverseAuthorityBeforeNewPeerEvidence() throws Exception {
        var fixture = new Fixture(); var actor = call(allowed()); fixture.reverse(actor).block();
        fixture.claimSelectedReverse(); var scopes = new HashSet<>(allowed()); scopes.remove("payments:reverse");
        StepVerifier.create(fixture.acknowledge(call(scopes))).expectErrorSatisfies(error ->
                assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                        value -> assertThat(value.getStatusCode().value()).isEqualTo(403))).verify();
        assertThat(fixture.peerReads.get()).isEqualTo(1);
        assertThat(fixture.acknowledgments.get()).isZero();
        assertThat(fixture.proofWrites.get()).isEqualTo(1);
    }

    @Test
    void mappedOriginSearchFindsExactCommittedHistoryAcrossPages() throws Exception {
        var effects = mock(io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentEffectRepository.class);
        var histories = mock(io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentHistoryRepository.class);
        var journal = mock(io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentJournalRepository.class);
        var nativeRepository = mock(io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentNativeRepository.class);
        var store = new ManagedPaymentStore(mock(io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentOperationRepository.class),
                mock(io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentActionRepository.class), effects,
                histories,mock(io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentMonetaryProofRepository.class),journal,nativeRepository);
        when(effects.findByIdTenantIdAndPaymentIdAndKind("owned",id(1),"COMMIT"))
                .thenReturn(Mono.just(new ManagedPaymentEffectEntity(new ManagedPaymentIds.EffectId("owned",id(20)),id(1),id(2),id(3),
                        id(21),"COMMIT","ACKNOWLEDGED",2L,2,null,null,null,null,null,null)));
        var rows = new ArrayList<ManagedPaymentHistoryEntity>();
        for(int version=0;version<=200;version++) {
            var event = new HistoryEvent(version,version==150?"LEDGER_COMMITTED":"CLAIMED","actor",Instant.EPOCH,
                    null,version==150?id(20):id(22),version==150?State.COMPLETED:State.COMMITTING,id(5),null);
            rows.add(new ManagedPaymentHistoryEntity(new ManagedPaymentIds.EventId("owned",id(1),version),id(2),id(3),
                    new PaymentJson(store.encode(event))));
        }
        var afters = new ArrayList<Long>();
        when(histories.findByIdTenantIdAndIdPaymentIdAndIdVersionGreaterThanOrderByIdVersionAsc(
                eq("owned"),eq(id(1)),anyLong(),any())).thenAnswer(invocation -> {
            long after = invocation.getArgument(2); afters.add(after);
            org.springframework.data.domain.Pageable page = invocation.getArgument(3);
            return reactor.core.publisher.Flux.fromIterable(rows).filter(row -> row.id().version()>after).take(page.getPageSize());
        });
        View current = completed(); for(int i=0;i<100;i++) current=ManagedPaymentStore.evolve(current,State.COMPLETED,null,null);
        var origin = store.commitOrigin(current).block();
        assertThat(origin.history().version()).isEqualTo(150);
        assertThat(origin.effect().participant()).isEqualTo(id(21));
        assertThat(afters).contains(-1L,99L);
        verifyNoInteractions(journal,nativeRepository);
    }

}
