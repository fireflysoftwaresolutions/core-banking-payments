package io.fireflysoftware.core.banking.payments.core.managed.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsContracts;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.*;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;
import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.View;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.*;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ManagedPaymentMonetaryIntegrityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static UUID id(int value) { return new UUID(0, value); }
    private static BigDecimal amount(String value) { return new BigDecimal(value); }
    private static BalanceVector balance(String a, String b, String c, String d) {
        return new BalanceVector(amount(a), amount(b), amount(c), amount(d));
    }
    private static MonetaryReceiptEnvelope receipt(boolean reverse) {
        var source = new EnrollmentSnapshot(id(2), id(12), id(22), "USD", 2, "policy", FundsContracts.Kind.CUSTOMER);
        var target = new EnrollmentSnapshot(id(3), id(13), id(23), "USD", 2, "policy", FundsContracts.Kind.CUSTOMER);
        var intent = new FundsContracts.Reserve(id(1), id(2), id(3), amount("1.23"), "USD", 300, 1700000300000L);
        var accepted = new AcceptedReserve("owned", id(4), intent, 1700000000000L, 1700000300000L, source, target);
        var effect = new MoneyEffect(amount("1.23"), "USD", source, target, "123", "123", reverse ? id(3) : id(2), reverse ? id(2) : id(3),
                reverse ? balance("8.77", "0", "10", "0") : balance("10", "1.23", "8.77", "0"),
                reverse ? balance("1.23", "0", "0", "0") : balance("0", "0", "1.23", "0"));
        return FundsReceiptCodec.envelope(new MonetaryReceipt(reverse ? id(8) : id(6), reverse ? id(8) : id(6), "owned", id(4), id(1),
                reverse ? EffectKind.REVERSE : EffectKind.COMMIT, reverse ? id(23) : id(21), accepted,
                reverse ? id(10) : id(5), reverse ? id(6) : null, reverse ? id(5) : null,
                reverse ? 1700000002000L : 1700000001000L, effect, new Actor("https://execution.invalid", "original-executor")));
    }
    private static MonetaryReceiptEnvelope forged(boolean reverse) {
        var original = receipt(reverse);var r = original.value();var e = r.effect();var b = e.sourceBalances();
        var effect = new MoneyEffect(e.amount(), e.currency(), e.source(), e.destination(), e.sourceMinorUnits(), e.destinationMinorUnits(),
                e.fromAccountId(), e.toAccountId(), new BalanceVector(b.bookedBefore().add(BigDecimal.ONE), b.heldBefore(),
                        b.bookedAfter().add(BigDecimal.ONE), b.heldAfter()), e.destinationBalances());
        var changed = new MonetaryReceipt(r.receiptId(), r.eventId(), r.tenant(), r.reservationId(), r.operationId(), r.effectKind(),
                r.actionId(), r.acceptedReserve(), r.postingId(), r.originalCommitReceiptId(), r.originalPostingId(),
                r.effectRecordedAtEpochMillis(), effect, r.actor());
        FundsReceiptCodec.verify(FundsReceiptCodec.envelope(changed));
        return new MonetaryReceiptEnvelope(changed, original.canonical());
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static VerifiedMonetaryProof summary(MonetaryReceiptEnvelope envelope) {
        var value = envelope.value();var form = envelope.canonical();
        return new VerifiedMonetaryProof(MonetaryKind.valueOf(value.effectKind().name()), value.receiptId(), value.actionId(),
                value.reservationId(), value.postingId(), value.originalCommitReceiptId(), value.originalPostingId(),
                form.encoding(), form.canonical(), form.digest(), encode(envelope));
    }
    private static VerifiedMonetaryProof change(VerifiedMonetaryProof proof, int index, Object replacement) {
        var values = new Object[] {proof.kind(), proof.receiptId(), proof.participantActionId(), proof.reservationId(), proof.postingId(),
                proof.originalCommitReceiptId(), proof.originalPostingId(), proof.encoding(), proof.canonical(), proof.digest(), proof.envelopeJson()};
        values[index] = replacement;
        return new VerifiedMonetaryProof((MonetaryKind)values[0], (UUID)values[1], (UUID)values[2], (UUID)values[3], (UUID)values[4],
                (UUID)values[5], (UUID)values[6], (String)values[7], (String)values[8], (String)values[9], (String)values[10]);
    }
    private static View view(boolean reverse) {
        return new View(id(1), id(100), "owned", id(2), id(3), "1.2300", "USD", 2, "policy", 300, null, 1700000300000L,
                "initiator", reverse ? ManagedPaymentContracts.State.REVERSED : ManagedPaymentContracts.State.REVERSING,
                reverse ? 102 : 101, id(4), id(5), reverse ? id(10) : null, null, reverse ? 3L : 1L,
                Instant.parse("2026-10-24T01:02:03Z"), reverse ? null : id(22), null, reverse ? "REVERSED" : "COMMITTED", Instant.ofEpochMilli(1700000300000L));
    }
    private static ManagedPaymentStoreImpl.EffectRow effect(boolean reverse) {
        return new ManagedPaymentStoreImpl.EffectRow(reverse ? id(22) : id(20), reverse ? id(23) : id(21),
                reverse ? ManagedPaymentContracts.EffectKind.REVERSE : ManagedPaymentContracts.EffectKind.COMMIT,
                "ACKNOWLEDGED", reverse ? 1 : 2, 2, null, null, reverse ? id(20) : null, reverse ? 100L : null, reverse ? id(4) : null);
    }
    private static VerifiedFundsObservation observed(boolean reverse) {
        return new VerifiedFundsObservation(reverse ? "REVERSED" : "COMMITTED", id(4), id(5), reverse ? id(10) : null,
                null, reverse ? 3L : 2L, Instant.parse("2026-10-24T01:02:03.123456Z"), Instant.ofEpochMilli(1700000300000L));
    }
    private static final class Fixture {
        final ManagedPaymentMonetaryProofRepository proofs = mock(ManagedPaymentMonetaryProofRepository.class);
        final ManagedPaymentJournalRepository journal = mock(ManagedPaymentJournalRepository.class);
        final List<ManagedPaymentMonetaryProofEntity> inserted = new ArrayList<>();
        final ManagedPaymentStoreImpl store = new ManagedPaymentStoreImpl(mock(ManagedPaymentOperationRepository.class), mock(ManagedPaymentActionRepository.class),
                mock(ManagedPaymentEffectRepository.class), mock(ManagedPaymentHistoryRepository.class), proofs, journal, mock(ManagedPaymentNativeRepository.class));
        Fixture() {
            when(journal.insertMonetaryProof(any())).thenAnswer(call -> {
                ManagedPaymentMonetaryProofEntity row = call.getArgument(0);inserted.add(row);return Mono.just(row);
            });
        }
        Mono<Void> retain(boolean reverse, VerifiedMonetaryProof proof) {
            return store.retainMonetaryProof(view(reverse), effect(reverse), proof, observed(reverse), "current-observer");
        }
        ManagedPaymentMonetaryProofEntity row() {
            var proof = summary(receipt(false));
            return new ManagedPaymentMonetaryProofEntity(new ManagedPaymentIds.ProofId("owned", id(20)), id(1), id(2), id(3), id(21), "COMMIT",
                    id(6), 101L, 2L, 2L, observed(false).observedAt().atOffset(java.time.ZoneOffset.UTC), "current-observer", new PaymentJson(proof.envelopeJson()), null);
        }
    }
    private static void refused(Mono<?> value, int status, String code) {
        StepVerifier.create(value).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                assertThat(failure.getStatusCode().value()).isEqualTo(status);
                assertThat(failure.getReason()).isEqualTo(code);
                assertThat(failure.getCause()).isNull();
            });
        }).verify();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void validCommitAndReverseRetainExactEnvelopeAndMetadataThroughMappedInsert(boolean reverse) {
        var f = new Fixture();var proof = summary(receipt(reverse));
        var work = f.retain(reverse, proof);
        assertThat(f.inserted).isEmpty();verifyNoInteractions(f.proofs);
        work.block();
        assertThat(f.inserted).hasSize(1);var row = f.inserted.getFirst();
        assertThat(row.receiptEnvelope().value()).isEqualTo(proof.envelopeJson());
        assertThat(row.id()).isEqualTo(new ManagedPaymentIds.ProofId("owned", effect(reverse).id()));
        assertThat(row.retainedVersion()).isEqualTo(view(reverse).version());
        assertThat(row.captureFence()).isEqualTo(effect(reverse).fence());
        assertThat(row.observedReservationVersion()).isEqualTo(observed(reverse).ledgerVersion());
        assertThat(row.observedAt()).isEqualTo(observed(reverse).observedAt().atOffset(java.time.ZoneOffset.UTC));
        verify(f.journal).insertMonetaryProof(row);verifyNoInteractions(f.proofs);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void forgedValueWithOriginalCanonicalCannotReachMappedInsert(boolean reverse) {
        var f = new Fixture();
        refused(f.retain(reverse, summary(forged(reverse))), 503, "VERIFIED_PAYMENT_MONETARY_PROOF_INVALID");
        assertThat(f.inserted).isEmpty();verifyNoInteractions(f.proofs);
    }
    static Stream<Arguments> wrongSummaries() {
        return Stream.of(Arguments.of(0, MonetaryKind.REVERSE), Arguments.of(1, id(99)), Arguments.of(2, id(99)),
                Arguments.of(3, id(99)), Arguments.of(4, id(99)), Arguments.of(5, id(99)), Arguments.of(6, id(99)),
                Arguments.of(7, "OTHER_ENCODING"), Arguments.of(8, "AAAA"), Arguments.of(9, "0".repeat(64)));
    }
    @ParameterizedTest @MethodSource("wrongSummaries")
    void everyProvidedProofSummaryFieldMustMatchTheVerifiedEnvelope(int index, Object replacement) {
        var f = new Fixture();
        refused(f.retain(false, change(summary(receipt(false)), index, replacement)), 409, "EXACT_PAYMENT_MONETARY_PROOF_REQUIRED");
        assertThat(f.inserted).isEmpty();verifyNoInteractions(f.proofs);
    }
    @ParameterizedTest @ValueSource(strings = {"not-json", "null", "{}", "[]"})
    void malformedProofIsOpaqueAndNeverWritten(String body) {
        var f = new Fixture();
        refused(f.retain(false, change(summary(receipt(false)), 10, body)), 503, "VERIFIED_PAYMENT_MONETARY_PROOF_INVALID");
        assertThat(f.inserted).isEmpty();
    }
    @Test void completeEnvelopeWithTrailingJsonCannotReachMappedInsert() {
        var f = new Fixture();var proof = summary(receipt(false));
        refused(f.retain(false, change(proof, 10, proof.envelopeJson() + " {}")), 503, "VERIFIED_PAYMENT_MONETARY_PROOF_INVALID");
        assertThat(f.inserted).isEmpty();
    }
    @Test void invalidEnvelopeValidationIsDeferredUntilTheOriginalSubscription() {
        var f = new Fixture();var proof = change(summary(receipt(false)), 0, null);
        Mono<Void> work = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> f.retain(false, proof));
        assertThat(f.inserted).isEmpty();
        refused(work, 409, "EXACT_PAYMENT_MONETARY_PROOF_REQUIRED");
    }
    @Test void validScopedReadReturnsTheSamePersistedRowWithoutAdditionalQueriesOrWrites() {
        var f = new Fixture();var row = f.row();var key = new ManagedPaymentIds.ProofId("owned", id(20));
        when(f.proofs.findById(key)).thenReturn(Mono.just(row));
        assertThat(f.store.monetaryProof("owned", id(20)).block()).isSameAs(row);
        verify(f.proofs).findById(key);verifyNoMoreInteractions(f.proofs);assertThat(f.inserted).isEmpty();
    }
    @Test void forgedLoadedReceiptFailsClosedBeforeReturningTheRow() {
        var f = new Fixture();var r = f.row();var key = new ManagedPaymentIds.ProofId("owned", id(20));
        var forged = new ManagedPaymentMonetaryProofEntity(r.id(), r.paymentId(), r.sourceAccountId(), r.destinationAccountId(),
                r.participantActionId(), r.kind(), r.receiptId(), r.retainedVersion(), r.captureFence(), r.observedReservationVersion(),
                r.observedAt(), r.observerSubject(), new PaymentJson(encode(forged(false))), r.retainedAt());
        when(f.proofs.findById(key)).thenReturn(Mono.just(forged));
        refused(f.store.monetaryProof("owned", id(20)), 503, "RETAINED_PAYMENT_MONETARY_PROOF_INVALID");
        verify(f.proofs).findById(key);verifyNoMoreInteractions(f.proofs);assertThat(f.inserted).isEmpty();
    }
    static Stream<Arguments> wrongRows() {
        return Stream.of(Arguments.of("tenant", "foreign"), Arguments.of("effect", id(99)), Arguments.of("payment", id(99)),
                Arguments.of("source", id(99)), Arguments.of("destination", id(99)), Arguments.of("participant", id(99)),
                Arguments.of("kind", "REVERSE"), Arguments.of("receipt", id(99)));
    }
    @ParameterizedTest @MethodSource("wrongRows")
    void scopedReadBindsEveryStoredSummaryIdentity(String field, Object changed) {
        var f = new Fixture();var r = f.row();var key = new ManagedPaymentIds.ProofId("owned", id(20));
        var row = new ManagedPaymentMonetaryProofEntity(new ManagedPaymentIds.ProofId(field.equals("tenant") ? (String)changed : "owned",
                        field.equals("effect") ? (UUID)changed : id(20)), field.equals("payment") ? (UUID)changed : r.paymentId(),
                field.equals("source") ? (UUID)changed : r.sourceAccountId(), field.equals("destination") ? (UUID)changed : r.destinationAccountId(),
                field.equals("participant") ? (UUID)changed : r.participantActionId(), field.equals("kind") ? (String)changed : r.kind(),
                field.equals("receipt") ? (UUID)changed : r.receiptId(), r.retainedVersion(), r.captureFence(), r.observedReservationVersion(),
                r.observedAt(), r.observerSubject(), r.receiptEnvelope(), r.retainedAt());
        when(f.proofs.findById(key)).thenReturn(Mono.just(row));
        refused(f.store.monetaryProof("owned", id(20)), 503, "RETAINED_PAYMENT_MONETARY_PROOF_INVALID");
        verify(f.proofs).findById(key);verifyNoMoreInteractions(f.proofs);assertThat(f.inserted).isEmpty();
    }

    @Test void duplicateEnvelopeFieldsCannotReachMappedInsert() {
        var f = new Fixture();var proof = summary(receipt(false));
        String duplicate = "{\"value\":" + encode(receipt(false).value()) + "," + proof.envelopeJson().substring(1);
        refused(f.retain(false, change(proof, 10, duplicate)), 503, "VERIFIED_PAYMENT_MONETARY_PROOF_INVALID");
        assertThat(f.inserted).isEmpty();
    }
    @Test void mappedInsertFailureIsNotReclassifiedAsInvalidProof() {
        var f = new Fixture();var failure = new IllegalStateException("owned-insert-failure");
        doReturn(Mono.error(failure)).when(f.journal).insertMonetaryProof(any());
        StepVerifier.create(f.retain(false, summary(receipt(false)))).expectErrorMatches(error -> error == failure).verify();
    }
    @Test void scopedReadPreservesEmptyAndOriginalRepositoryFailure() {
        var f = new Fixture();var key = new ManagedPaymentIds.ProofId("owned", id(20));
        when(f.proofs.findById(key)).thenReturn(Mono.empty());
        StepVerifier.create(f.store.monetaryProof("owned", id(20))).verifyComplete();
        var failure = new IllegalStateException("owned-read-failure");
        when(f.proofs.findById(key)).thenReturn(Mono.error(failure));
        StepVerifier.create(f.store.monetaryProof("owned", id(20))).expectErrorMatches(error -> error == failure).verify();
    }

    private static final class SourceTransactionManager extends AbstractReactiveTransactionManager {
        @java.io.Serial private static final long serialVersionUID = 1L;
        final AtomicInteger began = new AtomicInteger(), committed = new AtomicInteger(), rolledBack = new AtomicInteger();
        RuntimeException commitFailure;
        protected Object doGetTransaction(TransactionSynchronizationManager manager) { return new Object(); }
        protected Mono<Void> doBegin(TransactionSynchronizationManager m, Object t, TransactionDefinition d) {
            return Mono.fromRunnable(began::incrementAndGet);
        }
        protected Mono<Void> doCommit(TransactionSynchronizationManager m, GenericReactiveTransaction t) {
            return Mono.defer(() -> { committed.incrementAndGet();return commitFailure == null ? Mono.empty() : Mono.error(commitFailure); });
        }
        protected Mono<Void> doRollback(TransactionSynchronizationManager m, GenericReactiveTransaction t) {
            return Mono.fromRunnable(rolledBack::incrementAndGet);
        }
    }
    private static ManagedPaymentTransactionsImpl owner(Fixture f, SourceTransactionManager manager) {
        var context = mock(ManagedPaymentDatabaseContextImpl.class);
        when(context.establish(isNull(), anyString(), anyString(), anyString(), any(UUID[].class))).thenReturn(Mono.empty());
        return new ManagedPaymentTransactionsImpl(f.store, context,
                mock(io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.class), manager);
    }
    @Test void invalidCanonicalReceiptIsCheckedInsideOriginalOwnerAndRollsBack() {
        var f = new Fixture();var manager = new SourceTransactionManager();
        var work = owner(f, manager).transaction(null, "owned", "OBSERVE", "payments:reverse", new UUID[0],
                () -> { assertThat(manager.began).hasValue(1);return f.retain(false, summary(forged(false))); });
        assertThat(manager.began).hasValue(0);assertThat(f.inserted).isEmpty();
        refused(work, 503, "VERIFIED_PAYMENT_MONETARY_PROOF_INVALID");
        assertThat(manager.rolledBack).hasValue(1);assertThat(manager.committed).hasValue(0);assertThat(f.inserted).isEmpty();
    }
    @Test void originalOwnerPreservesMappedFailureAndRollsBack() {
        var f = new Fixture();var manager = new SourceTransactionManager();
        var failure = new IllegalStateException("owned-journal-outcome");
        doReturn(Mono.error(failure)).when(f.journal).insertMonetaryProof(any());
        var work = owner(f, manager).transaction(null, "owned", "OBSERVE", "payments:reverse", new UUID[0],
                () -> f.retain(false, summary(receipt(false))));
        StepVerifier.create(work).expectErrorMatches(error -> error == failure).verify();
        assertThat(manager.rolledBack).hasValue(1);assertThat(manager.committed).hasValue(0);
    }
    @Test void originalOwnerCommitFailureIsNotReclassifiedAsInvalidProof() {
        var f = new Fixture();var manager = new SourceTransactionManager();
        manager.commitFailure = new IllegalStateException("owned-commit-outcome");
        var work = owner(f, manager).transaction(null, "owned", "OBSERVE", "payments:reverse", new UUID[0],
                () -> f.retain(false, summary(receipt(false))));
        StepVerifier.create(work).expectErrorMatches(error -> error == manager.commitFailure).verify();
        assertThat(manager.committed).hasValue(1);
    }
    @Test void cancellationStillUsesOriginalOwnerRollback() {
        var f = new Fixture();var manager = new SourceTransactionManager();
        doReturn(Mono.never()).when(f.journal).insertMonetaryProof(any());
        var work = owner(f, manager).transaction(null, "owned", "OBSERVE", "payments:reverse", new UUID[0],
                () -> f.retain(false, summary(receipt(false))));
        var subscription = work.subscribe();
        assertThat(manager.began).hasValue(1);
        subscription.dispose();
        assertThat(manager.rolledBack).hasValue(1);assertThat(manager.committed).hasValue(0);
    }
}
