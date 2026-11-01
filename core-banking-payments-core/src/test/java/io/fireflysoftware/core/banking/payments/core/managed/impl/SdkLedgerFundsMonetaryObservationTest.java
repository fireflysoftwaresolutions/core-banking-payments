package io.fireflysoftware.core.banking.payments.core.managed.impl;

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.banking.ledger.sdk.api.LedgerFundsApi;
import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import io.fireflysoftware.core.banking.ledger.sdk.invoker.ApiClient;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsContracts;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.*;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;
import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts;


class SdkLedgerFundsMonetaryObservationTest {
    @Test
    void consumerUsesPublicPortableContractWithoutOwnerCore() {
        assertThatCode(() -> Class.forName(
                "io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> Class.forName(
                "io.fireflysoftware.core.banking.ledger.core.services.funds.FundsReceiptCodec"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void genuineRegeneratedClientIncludesReceiptRead() throws Exception {
        assertThat(LedgerFundsApi.class.getMethod("getFundsMonetaryReceipts",
                String.class, UUID.class, String.class).getReturnType().getName())
                .isEqualTo("reactor.core.publisher.Mono");
    }

    @Test
    void monetaryObservationRequiresSeparateSelectedReceiptContract() {
        assertThat(Arrays.stream(LedgerFundsObservationPort.class.getMethods())
                .anyMatch(method -> method.getName().equals("observeMonetary")))
                .as("current reservation state alone cannot prove a selected financial action")
                .isTrue();
    }
    private static final long NOW = 1700000000000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static UUID id(int n) { return new UUID(0, n); }

    private static AcceptedReserve accepted() {
        return new AcceptedReserve("owned", id(4),
                new FundsContracts.Reserve(id(1), id(2), id(3), new BigDecimal("1.23"),
                        "USD", 300, NOW + 300000), NOW, NOW + 300000,
                new EnrollmentSnapshot(id(2), id(12), id(22), "USD", 2, "policy", FundsContracts.Kind.CUSTOMER),
                new EnrollmentSnapshot(id(3), id(13), id(23), "USD", 2, "policy", FundsContracts.Kind.CUSTOMER));
    }

    private static BalanceVector balance(String a, String b, String c, String d) {
        return new BalanceVector(new BigDecimal(a), new BigDecimal(b), new BigDecimal(c), new BigDecimal(d));
    }

    private static MonetaryReceipt monetary(boolean reverse) {
        var a = accepted();
        var effect = new MoneyEffect(new BigDecimal("1.23"), "USD", a.source(), a.destination(),
                "123", "123", reverse ? id(3) : id(2), reverse ? id(2) : id(3),
                reverse ? balance("8.77", "0", "10", "0") : balance("10", "1.23", "8.77", "0"),
                reverse ? balance("1.23", "0", "0", "0") : balance("0", "0", "1.23", "0"));
        return new MonetaryReceipt(reverse ? id(8) : id(6), reverse ? id(8) : id(6), "owned", id(4), id(1),
                reverse ? EffectKind.REVERSE : EffectKind.COMMIT, reverse ? id(9) : id(7), a,
                reverse ? id(10) : id(5), reverse ? id(6) : null, reverse ? id(5) : null,
                NOW + (reverse ? 2000 : 1000), effect, new Actor("https://execution.invalid", "original-executor"));
    }

    private static ManagedPaymentContracts.View view() {
        return new ManagedPaymentContracts.View(id(1), id(100), "owned", id(2), id(3), "1.23", "USD", 2,
                "policy", 300, null, NOW + 300000, "initiator", ManagedPaymentContracts.State.COMPLETED,
                100, id(4), id(5), null, null, 1L, Instant.ofEpochMilli(NOW + 1000), null,
                null, "COMMITTED", Instant.ofEpochMilli(NOW + 300000));
    }

    private static Map<String,Object> reservation(long version, boolean reverse) {
        var m = new LinkedHashMap<String,Object>();
        m.put("reservationId", id(4)); m.put("operationId", id(1));
        m.put("sourceAccountId", id(2)); m.put("destinationAccountId", id(3));
        m.put("amount", new BigDecimal("1.23")); m.put("currency", "USD");
        m.put("postingId", id(5)); m.put("reversalId", reverse ? id(10) : null);
        m.put("version", version); m.put("state", reverse ? "REVERSED" : "COMMITTED");
        m.put("expiresAt", Instant.ofEpochMilli(NOW + 300000).toString());
        return m;
    }

    private static Map<String,Object> observed(long version, boolean reverse) {
        var m = new LinkedHashMap<String,Object>();
        m.put("outcome", "FOUND"); m.put("receiptId", null);
        m.put("notAfterEpochMillis", NOW + 300000);
        m.put("observedAt", Instant.ofEpochMilli(NOW + 5000).toString());
        m.put("intent", accepted().intent()); m.put("reservation", reservation(version, reverse));
        return m;
    }

    private static Map<String,Object> evidence(long version, boolean reverse) {
        var m = new LinkedHashMap<String,Object>();
        m.put("evidenceStatus", "RETAINED"); m.put("acceptedReserve", FundsReceiptCodec.envelope(accepted()));
        m.put("commit", FundsReceiptCodec.envelope(monetary(false)));
        m.put("reversal", reverse ? FundsReceiptCodec.envelope(monetary(true)) : null);
        m.put("currentReservation", reservation(version, reverse)); return m;
    }

    private static MonetaryExpectation expected(boolean reverse) {
        return new MonetaryExpectation(reverse ? MonetaryKind.REVERSE : MonetaryKind.COMMIT,
                reverse ? id(9) : id(7), reverse ? id(7) : null, reverse ? id(6) : null,
                reverse ? FundsReceiptCodec.canonicalReceipt(monetary(false)).digest() : null,
                reverse ? id(5) : null, 1L);
    }

    private static final class Scripted extends SdkLedgerFundsObservationAdapter {
        final Queue<String> bodies = new ArrayDeque<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger authorizations = new AtomicInteger();
        boolean permitted = true;
        Scripted(Object... values) throws Exception {
            super((a,t,r,s) -> Mono.error(new AssertionError("scripted unit transport only")),
                    "http://127.0.0.1:9", "ledger-funds", true);
            for (Object value : values) bodies.add(JSON.writeValueAsString(value));
        }
        @Override
        Mono<LedgerFundsApi> client(String tenant, UUID source, UUID destination, Set<String> scopes) {
            return Mono.defer(() -> {
                authorizations.incrementAndGet();
                if (!permitted) return Mono.error(ManagedPaymentPolicy.fail(403, "CURRENT_UNIT_AUTHORITY_DENIED"));
                var web = WebClient.builder().exchangeFunction(request -> {
                    reads.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                            .body(Objects.requireNonNull(bodies.poll(), "unexpected SDK request")).build());
                }).build();
                return Mono.just(new LedgerFundsApi(new ApiClient(web)));
            });
        }
    }

    private static void denied(Scripted source, MonetaryExpectation expectation,
            ManagedPaymentContracts.FailureCode code) {
        StepVerifier.create(source.observeMonetary("owned", view(), expectation))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(
                        ObservationUnavailable.class, value -> assertThat(value.code()).isEqualTo(code)))
                .verify();
    }

    @Test
    void ownerVersionTwoIsValidAlthoughCoreJournalVersionIsOneHundred() throws Exception {
        var source = new Scripted(observed(2, false), evidence(2, false));
        var result = source.observeMonetary("owned", view(), expected(false)).block();
        assertThat(result.funds().ledgerVersion()).isEqualTo(2);
        assertThat(result.funds().observedAt()).isEqualTo(Instant.ofEpochMilli(NOW + 5000));
        assertThat(result.commit().participantActionId()).isEqualTo(id(7));
        assertThat(result.commit().canonical()).isEqualTo(FundsReceiptCodec.canonicalReceipt(monetary(false)).canonical());
        assertThat(source.reads.get()).isEqualTo(2);
    }

    @Test
    void differingOwnerVersionsCannotBeSplicedAndRetryStartsBothReads() throws Exception {
        var source = new Scripted(observed(2, false), evidence(3, false), observed(3, false), evidence(3, false));
        denied(source, expected(false), ManagedPaymentContracts.FailureCode.OBSERVATION_UNAVAILABLE);
        assertThat(source.reads.get()).isEqualTo(2);
        assertThat(source.observeMonetary("owned", view(), expected(false)).block().funds().ledgerVersion()).isEqualTo(3);
        assertThat(source.reads.get()).isEqualTo(4);
        assertThat(source.authorizations.get()).isEqualTo(4);
    }

    @Test
    void sameVersionButDifferentOwnerStateCannotBeCombined() throws Exception {
        var source = new Scripted(observed(2, false), evidence(2, true));
        denied(source, expected(false), ManagedPaymentContracts.FailureCode.OBSERVATION_UNAVAILABLE);
        assertThat(source.reads.get()).isEqualTo(2);
    }

    @Test
    void priorOwnerProofVersionCannotRegress() throws Exception {
        var source = new Scripted(observed(2, false), evidence(2, false));
        var e = expected(false);
        denied(source, new MonetaryExpectation(e.kind(), e.selectedActionId(), null, null, null, null, 3L),
                ManagedPaymentContracts.FailureCode.INCONSISTENT_OBSERVATION);
    }

    @Test
    void selectedActionMismatchCannotAcknowledgeCurrentCommittedState() throws Exception {
        var source = new Scripted(observed(2, false), evidence(2, false));
        denied(source, new MonetaryExpectation(MonetaryKind.COMMIT, id(77), null, null, null, null, 1L),
                ManagedPaymentContracts.FailureCode.INCONSISTENT_OBSERVATION);
    }

    @Test
    void committedWithoutReversalReceiptRemainsUnavailable() throws Exception {
        var source = new Scripted(observed(2, false), evidence(2, false));
        denied(source, expected(true), ManagedPaymentContracts.FailureCode.OBSERVATION_UNAVAILABLE);
        assertThat(source.reads.get()).isEqualTo(2);
    }

    @Test
    void selectedReverseRetainsExecutorWithoutRequiringCurrentReaderEquality() throws Exception {
        var source = new Scripted(observed(3, true), evidence(3, true));
        var result = source.observeMonetary("owned", view(), expected(true)).block();
        assertThat(result.reversal().participantActionId()).isEqualTo(id(9));
        assertThat(result.reversal().originalCommitReceiptId()).isEqualTo(id(6));
        assertThat(result.reversal().envelopeJson()).contains("original-executor");
        assertThat(source.reads.get()).isEqualTo(2);
    }

    @Test
    void eachRetryRechecksAuthorityBeforeEitherNewOwnerRead() throws Exception {
        var source = new Scripted(observed(2, false), evidence(3, false));
        denied(source, expected(false), ManagedPaymentContracts.FailureCode.OBSERVATION_UNAVAILABLE);
        source.permitted = false;
        StepVerifier.create(source.observeMonetary("owned", view(), expected(false)))
                .expectError(org.springframework.web.server.ResponseStatusException.class).verify();
        assertThat(source.reads.get()).isEqualTo(2);
        assertThat(source.authorizations.get()).isEqualTo(3);
    }

    @Test
    void malformedClaimedDigestCannotBecomeVerifiedProof() throws Exception {
        var body = evidence(2, false);
        var original = FundsReceiptCodec.envelope(monetary(false));
        body.put("commit", new MonetaryReceiptEnvelope(original.value(),
                new CanonicalForm(original.canonical().encoding(), original.canonical().canonical(), "0".repeat(64))));
        denied(new Scripted(observed(2, false), body), expected(false),
                ManagedPaymentContracts.FailureCode.INCONSISTENT_OBSERVATION);
    }

    @Test
    void wrongOriginalCommitActionCannotAuthorizeSelectedReverse() throws Exception {
        var e = expected(true);
        denied(new Scripted(observed(3, true), evidence(3, true)),
                new MonetaryExpectation(e.kind(), e.selectedActionId(), id(77), e.originalCommitReceiptId(),
                        e.originalCommitDigest(), e.originalPostingId(), e.minimumOwnerVersion()),
                ManagedPaymentContracts.FailureCode.INCONSISTENT_OBSERVATION);
    }

    @Test
    void reversedStateWithAnotherSelectedActionCannotAcknowledge() throws Exception {
        var e = expected(true);
        denied(new Scripted(observed(3, true), evidence(3, true)),
                new MonetaryExpectation(e.kind(), id(99), e.originalCommitActionId(), e.originalCommitReceiptId(),
                        e.originalCommitDigest(), e.originalPostingId(), e.minimumOwnerVersion()),
                ManagedPaymentContracts.FailureCode.INCONSISTENT_OBSERVATION);
    }

    @Test
    void wrongRetainedOriginalDigestCannotLinkCurrentReverse() throws Exception {
        var e = expected(true);
        denied(new Scripted(observed(3, true), evidence(3, true)),
                new MonetaryExpectation(e.kind(), e.selectedActionId(), e.originalCommitActionId(),
                        e.originalCommitReceiptId(), "0".repeat(64), e.originalPostingId(), e.minimumOwnerVersion()),
                ManagedPaymentContracts.FailureCode.INCONSISTENT_OBSERVATION);
    }

    @Test
    void historicalUnavailableEvidenceCannotBeFabricated() throws Exception {
        var body = evidence(2, false); body.put("evidenceStatus", "HISTORICAL_UNAVAILABLE");
        body.put("acceptedReserve", null); body.put("commit", null);
        denied(new Scripted(observed(2, false), body), expected(false),
                ManagedPaymentContracts.FailureCode.OBSERVATION_UNAVAILABLE);
    }

    @Test
    void sameOwnerVersionWithChangedPostingRequiresFreshPairOfReads() throws Exception {
        var body = evidence(2, false);
        var current = reservation(2, false); current.put("postingId", id(55));
        body.put("currentReservation", current);
        var source = new Scripted(observed(2, false), body);
        denied(source, expected(false), ManagedPaymentContracts.FailureCode.OBSERVATION_UNAVAILABLE);
        assertThat(source.reads.get()).isEqualTo(2);
    }

    @Test
    void sameOwnerVersionWithChangedExpiryCannotBeSpliced() throws Exception {
        var body = evidence(2, false);
        var current = reservation(2, false); current.put("expiresAt", Instant.ofEpochMilli(NOW + 200000).toString());
        body.put("currentReservation", current);
        denied(new Scripted(observed(2, false), body), expected(false),
                ManagedPaymentContracts.FailureCode.OBSERVATION_UNAVAILABLE);
    }

    @Test
    void monetaryPeerReadsRefuseAnActiveCoreTransaction() throws Exception {
        var source = new Scripted(observed(2, false), evidence(2, false));
        var attempt = org.springframework.transaction.reactive.TransactionSynchronizationManager.forCurrentTransaction()
                .flatMap(manager -> {
                    manager.setActualTransactionActive(true);
                    return source.observeMonetary("owned", view(), expected(false));
                }).contextWrite(org.springframework.transaction.reactive.TransactionContextManager.createTransactionContext());
        StepVerifier.create(attempt).expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        value -> assertThat(value.getStatusCode().value()).isEqualTo(503))).verify();
        assertThat(source.reads.get()).isZero();
        assertThat(source.authorizations.get()).isZero();
    }

}
