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

package io.fireflysoftware.core.banking.payments.core.managed.impl;

import io.fireflysoftware.core.banking.payments.core.managed.*;

import static io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicyImpl.fail;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.MonetaryReceiptEnvelope;
import io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.*;
import java.util.*;

@Component("managedPaymentStore")
public final class ManagedPaymentStoreImpl {
    private static final ObjectReader MONETARY_RECEIPT = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readerFor(MonetaryReceiptEnvelope.class);
    private final ManagedPaymentOperationRepository operations;
    private final ManagedPaymentActionRepository actions;
    private final ManagedPaymentEffectRepository effects;
    private final ManagedPaymentHistoryRepository histories;
    private final ManagedPaymentMonetaryProofRepository monetaryProofs;
    private final ManagedPaymentJournalRepository journal;
    private final ManagedPaymentNativeRepository nativeRepository;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ManagedPaymentStoreImpl(ManagedPaymentOperationRepository operations,
            ManagedPaymentActionRepository actions, ManagedPaymentEffectRepository effects,
            ManagedPaymentHistoryRepository histories, ManagedPaymentMonetaryProofRepository monetaryProofs,
            ManagedPaymentJournalRepository journal,
            ManagedPaymentNativeRepository nativeRepository) {
        this.operations = operations; this.actions = actions; this.effects = effects;
        this.histories = histories; this.monetaryProofs = monetaryProofs; this.journal = journal; this.nativeRepository = nativeRepository;
    }

    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("PAYMENT_INTERNAL_ENCODING_FAILED", e); }
    }

    View decode(String value) {
        try { return json.readValue(value, View.class); }
        catch (Exception e) { throw new IllegalStateException("PAYMENT_RETAINED_RECORD_INVALID", e); }
    }

    <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("PAYMENT_RETAINED_RECEIPT_INVALID", e); }
    }

    Mono<Void> gate(String tenant, UUID id) {
        return nativeRepository.lockPayment("managed-payment:" + tenant + ":" + id);
    }

    Mono<Void> accountGates(String tenant, UUID... accounts) {
        return Flux.fromIterable(Arrays.stream(accounts).distinct()
                        .sorted(Comparator.comparing(UUID::toString)).toList())
                .concatMap(account -> nativeRepository.lockAccount(tenant, account)).then();
    }

    Mono<View> byAction(String tenant, UUID action) {
        return actions.findByIdTenantIdAndIdActionIdAndKind(tenant, action, "CREATE")
                .flatMap(row -> read(tenant, row.paymentId()));
    }

    Mono<View> read(String tenant, UUID payment) {
        return operations.findById(new ManagedPaymentIds.OperationId(tenant, payment))
                .map(row -> decode(row.payload().value()));
    }

    Mono<Long> now() { return nativeRepository.now(); }

    private ManagedPaymentOperationEntity operation(View v, String digest) {
        return new ManagedPaymentOperationEntity(new ManagedPaymentIds.OperationId(v.tenant(), v.paymentId()),
                v.sourceAccountId(), v.destinationAccountId(), v.createActionId(), digest,
                new PaymentJson(encode(v)), v.state().name(), v.version());
    }

    private ManagedPaymentProjectionEntity projection(View v) {
        return new ManagedPaymentProjectionEntity(new ManagedPaymentIds.OperationId(v.tenant(), v.paymentId()),
                v.sourceAccountId(), v.destinationAccountId(), new PaymentJson(encode(v)));
    }

    private ManagedPaymentEffectEntity effectEntity(View v, UUID id, UUID participant, EffectKind kind) {
        return new ManagedPaymentEffectEntity(new ManagedPaymentIds.EffectId(v.tenant(), id), v.paymentId(),
                v.sourceAccountId(), v.destinationAccountId(), participant, kind.name(), "PENDING", 0L, 0,
                null, null, null, null, null, null);
    }

    Mono<Void> create(View v, String digest, UUID effect) {
        return journal.insertOperation(operation(v, digest))
                .then(insertAction(v, v.createActionId(), "CREATE", digest, v.initiator(), null))
                .then(journal.insertEffect(effectEntity(v, effect, v.paymentId(), EffectKind.RESERVE)))
                .then(journal.insertProjection(projection(v)))
                .then(event(v, "CREATED", v.initiator(), v.createActionId(), effect));
    }

    Mono<Void> event(View v, String type, String actor, UUID action, UUID effect) {
        var payload = new PaymentJson(encode(new HistoryEvent(v.version(), type, actor,
                java.time.Instant.now(), action, effect, v.state(), v.postingId(), v.reversalId())));
        var id = new ManagedPaymentIds.EventId(v.tenant(), v.paymentId(), v.version());
        return journal.insertHistory(new ManagedPaymentHistoryEntity(id, v.sourceAccountId(), v.destinationAccountId(), payload))
                .then(journal.insertOutbox(new ManagedPaymentOutboxEntity(id, v.sourceAccountId(),
                        v.destinationAccountId(), UUID.randomUUID(), payload))).then();
    }

    Flux<HistoryEvent> history(String tenant, UUID id, long after, int limit) {
        return histories.findByIdTenantIdAndIdPaymentIdAndIdVersionGreaterThanOrderByIdVersionAsc(
                tenant, id, after, PageRequest.of(0, limit))
                .map(row -> decode(row.payload().value(), HistoryEvent.class));
    }

    record RetainedAction(UUID payment, String kind, String digest, String actor, String receipt) {}
    record EffectRow(UUID id, UUID participant, EffectKind kind, String state, long fence,
            int attempts, String owner, java.time.Instant lease, UUID originalEffectId,
            Long originalCommitVersion, UUID originalReservationId) {
        EffectRow(UUID id, UUID participant, EffectKind kind, String state, long fence,
                int attempts, String owner, java.time.Instant lease) {
            this(id, participant, kind, state, fence, attempts, owner, lease, null, null, null);
        }
    }
    record CommitOrigin(EffectRow effect, HistoryEvent history) {}

    Mono<RetainedAction> action(String tenant, UUID action) {
        return actions.findById(new ManagedPaymentIds.ActionId(tenant, action))
                .map(row -> new RetainedAction(row.paymentId(), row.kind(), row.intentDigest(),
                        row.actorSubject(), row.receipt() == null ? null : row.receipt().value()));
    }

    Mono<Void> insertAction(View v, UUID action, String kind, String digest, String actor, Object receipt) {
        return journal.insertAction(new ManagedPaymentActionEntity(new ManagedPaymentIds.ActionId(v.tenant(), action),
                v.paymentId(), v.sourceAccountId(), v.destinationAccountId(), kind, digest, actor,
                receipt == null ? null : new PaymentJson(encode(receipt))))
                .onErrorMap(this::actionFailure).then();
    }

    private Throwable actionFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.r2dbc.postgresql.api.PostgresqlException pg
                    && "23505".equals(pg.getErrorDetails().getCode())
                    && pg.getErrorDetails().getConstraintName().filter("managed_payment_action_pkey"::equals).isPresent())
                return fail(409, "PAYMENT_ACTION_CONFLICT");
        }
        return error;
    }

    private EffectRow effectRow(ManagedPaymentEffectEntity row) {
        return new EffectRow(row.id().effectId(), row.participantActionId(), EffectKind.valueOf(row.kind()),
                row.state(), row.fence(), row.attempts(), row.ownerSubject(),
                row.leaseUntil() == null ? null : row.leaseUntil().toInstant(),
                row.originalEffectId(), row.originalCommitVersion(), row.originalReservationId());
    }

    Mono<EffectRow> effect(String tenant, UUID id) {
        return effects.findById(new ManagedPaymentIds.EffectId(tenant, id)).map(this::effectRow)
                .switchIfEmpty(Mono.error(fail(409, "RETAINED_PAYMENT_EFFECT_REQUIRED")));
    }

    private ManagedPaymentJournalOperations.EffectVersion expected(String tenant, EffectRow row) {
        return new ManagedPaymentJournalOperations.EffectVersion(
                new ManagedPaymentIds.EffectId(tenant, row.id()), row.fence(), row.attempts());
    }

    private Mono<Void> requireOne(Mono<Long> rows, String reason) {
        return rows.filter(n -> n == 1L).switchIfEmpty(Mono.error(fail(409, reason))).then();
    }

    Mono<Void> claimEffect(String tenant, EffectRow effect, String actor, java.time.Instant until) {
        return requireOne(journal.claimEffect(expected(tenant, effect), actor, until), "PAYMENT_EFFECT_FENCE_CONFLICT");
    }

    Mono<Void> save(View v, String type, String actor, UUID action, UUID effect) {
        return requireOne(journal.saveOperation(new ManagedPaymentJournalOperations.OperationUpdate(
                        new ManagedPaymentIds.OperationId(v.tenant(), v.paymentId()),
                        new PaymentJson(encode(v)), v.state().name(), v.version()), v.version() - 1), "PAYMENT_VERSION_CONFLICT")
                .then(requireOne(journal.saveProjection(projection(v)), "PAYMENT_PROJECTION_REQUIRED"))
                .then(event(v, type, actor, action, effect));
    }

    static View evolve(View v, State state, UUID pending, String code) {
        if (v.version() == Long.MAX_VALUE) throw fail(409, "PAYMENT_VERSION_EXHAUSTED");
        return new View(
                v.paymentId(),
                v.createActionId(),
                v.tenant(),
                v.sourceAccountId(),
                v.destinationAccountId(),
                v.amount(),
                v.currency(),
                v.currencyScale(),
                v.currencyPolicy(),
                v.ttlSeconds(),
                v.customerNotAfterEpochMillis(),
                v.notAfterEpochMillis(),
                v.initiator(),
                state,
                v.version() + 1,
                v.reservationId(),
                v.postingId(),
                v.reversalId(),
                v.absenceReceiptId(),
                v.ledgerVersion(),
                v.observedAt(),
                pending,
                code,
                v.ledgerState(),
                v.reservationExpiresAt());
    }

    Mono<Void> deferEffect(String tenant, EffectRow effect) {
        int delay = Math.min(30, 1 << Math.min(5, Math.max(0, effect.attempts() - 1)));
        return requireOne(Mono.defer(() -> nativeRepository.databaseTime().flatMap(now ->
                journal.rescheduleEffect(expected(tenant, effect), effect.attempts() >= 10 ? "EXHAUSTED" : "PENDING",
                        now.toInstant().plusSeconds(delay)))), "PAYMENT_EFFECT_FENCE_CONFLICT");
    }

    Flux<View> queue(String tenant, UUID after, int limit) {
        return nativeRepository.recoveryQueue(tenant, after, limit).map(row -> decode(row.payload().value()));
    }

    Mono<Void> acknowledgeEffect(String tenant, EffectRow effect) {
        return requireOne(journal.acknowledgeEffect(expected(tenant, effect)), "PAYMENT_EFFECT_FENCE_CONFLICT");
    }

    Mono<EffectRow> insertEffect(View v, EffectKind kind) {
        UUID id = UUID.randomUUID(), participant = UUID.randomUUID();
        return journal.insertEffect(effectEntity(v, id, participant, kind))
                .thenReturn(new EffectRow(id, participant, kind, "PENDING", 0, 0, null, null));
    }

    Mono<EffectRow> observationEffect(View v) {
        return effects.findByIdTenantIdAndPaymentIdAndKind(v.tenant(), v.paymentId(), "OBSERVE")
                .map(this::effectRow).switchIfEmpty(Mono.defer(() -> insertEffect(v, EffectKind.OBSERVE)));
    }

    Mono<Void> resetEffect(String tenant, EffectRow effect) {
        return requireOne(Mono.defer(() -> nativeRepository.databaseTime().flatMap(now ->
                journal.rescheduleEffect(expected(tenant, effect), "PENDING", now.toInstant()))),
                "PAYMENT_EFFECT_FENCE_CONFLICT");
    }
    Mono<CommitOrigin> commitOrigin(View parent) {
        return effects.findByIdTenantIdAndPaymentIdAndKind(parent.tenant(), parent.paymentId(), "COMMIT")
                .map(this::effectRow)
                .filter(effect -> effect.kind() == EffectKind.COMMIT && "ACKNOWLEDGED".equals(effect.state()))
                .flatMap(effect -> historyPages(parent.tenant(), parent.paymentId(), -1)
                        .filter(row -> {
                            var event = decode(row.payload().value(), HistoryEvent.class);
                            return event.version() == row.id().version() && event.version() <= parent.version()
                                    && "LEDGER_COMMITTED".equals(event.type()) && event.state() == State.COMPLETED
                                    && Objects.equals(event.effectId(), effect.id())
                                    && Objects.equals(event.postingId(), parent.postingId()) && event.reversalId() == null;
                        }).next().map(row -> new CommitOrigin(effect, decode(row.payload().value(), HistoryEvent.class))))
                .switchIfEmpty(Mono.error(fail(409, "RETAINED_PAYMENT_COMMIT_ORIGIN_REQUIRED")));
    }

    private Flux<ManagedPaymentHistoryEntity> historyPages(String tenant, UUID payment, long after) {
        return histories.findByIdTenantIdAndIdPaymentIdAndIdVersionGreaterThanOrderByIdVersionAsc(
                        tenant, payment, after, PageRequest.of(0, 100)).collectList()
                .expand(rows -> rows.size() == 100 && rows.getLast().id().version() < Long.MAX_VALUE
                        ? histories.findByIdTenantIdAndIdPaymentIdAndIdVersionGreaterThanOrderByIdVersionAsc(
                                tenant, payment, rows.getLast().id().version(), PageRequest.of(0, 100)).collectList()
                        : Mono.empty())
                .concatMapIterable(rows -> rows);
    }

    Mono<EffectRow> insertReverseEffect(View parent, CommitOrigin origin) {
        UUID id = UUID.randomUUID(), participant = UUID.randomUUID();
        var row = new ManagedPaymentEffectEntity(new ManagedPaymentIds.EffectId(parent.tenant(), id),
                parent.paymentId(), parent.sourceAccountId(), parent.destinationAccountId(), participant,
                "REVERSE", "PENDING", 0L, 0, null, null, null, origin.effect().id(),
                origin.history().version(), parent.reservationId());
        return journal.insertEffect(row).map(this::effectRow);
    }

    private static MonetaryReceiptEnvelope verifiedMonetaryEnvelope(String encoded, String failureCode) {
        try {
            MonetaryReceiptEnvelope envelope = MONETARY_RECEIPT.readValue(encoded);
            FundsReceiptCodec.verify(envelope);
            return envelope;
        } catch (Exception invalid) {
            throw fail(503, failureCode);
        }
    }

    Mono<ManagedPaymentMonetaryProofEntity> monetaryProof(String tenant, UUID effect) {
        return monetaryProofs.findById(new ManagedPaymentIds.ProofId(tenant, effect)).map(row -> {
            var envelope = verifiedMonetaryEnvelope(
                    row.receiptEnvelope() == null ? null : row.receiptEnvelope().value(),
                    "RETAINED_PAYMENT_MONETARY_PROOF_INVALID");
            var value = envelope.value();
            if (row.id() == null || !Objects.equals(tenant, row.id().tenantId())
                    || !Objects.equals(effect, row.id().effectId())
                    || !Objects.equals(row.id().tenantId(), value.tenant())
                    || !Objects.equals(row.paymentId(), value.operationId())
                    || !Objects.equals(row.sourceAccountId(), value.effect().source().accountId())
                    || !Objects.equals(row.destinationAccountId(), value.effect().destination().accountId())
                    || !Objects.equals(row.participantActionId(), value.actionId())
                    || !Objects.equals(row.kind(), value.effectKind().name())
                    || !Objects.equals(row.receiptId(), value.receiptId()))
                throw fail(503, "RETAINED_PAYMENT_MONETARY_PROOF_INVALID");
            return row;
        });
    }

    Mono<Void> retainMonetaryProof(View next, EffectRow effect,
            io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.VerifiedMonetaryProof proof,
            io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.VerifiedFundsObservation observation,
            String observer) {
        return Mono.defer(() -> {
            if (proof == null || proof.kind() == null || observation.ledgerVersion() == null || observation.observedAt() == null
                    || !Objects.equals(effect.participant(), proof.participantActionId())
                    || !effect.kind().name().equals(proof.kind().name())
                    || !Objects.equals(next.reservationId(), proof.reservationId()))
                return Mono.error(fail(409, "EXACT_PAYMENT_MONETARY_PROOF_REQUIRED"));
            var envelope = verifiedMonetaryEnvelope(proof.envelopeJson(), "VERIFIED_PAYMENT_MONETARY_PROOF_INVALID");
            var value = envelope.value();
            var canonical = envelope.canonical();
            if (!Objects.equals(proof.kind().name(), value.effectKind().name())
                    || !Objects.equals(proof.receiptId(), value.receiptId())
                    || !Objects.equals(proof.participantActionId(), value.actionId())
                    || !Objects.equals(proof.reservationId(), value.reservationId())
                    || !Objects.equals(proof.postingId(), value.postingId())
                    || !Objects.equals(proof.originalCommitReceiptId(), value.originalCommitReceiptId())
                    || !Objects.equals(proof.originalPostingId(), value.originalPostingId())
                    || !Objects.equals(proof.encoding(), canonical.encoding())
                    || !Objects.equals(proof.canonical(), canonical.canonical())
                    || !Objects.equals(proof.digest(), canonical.digest())
                    || !Objects.equals(next.tenant(), value.tenant())
                    || !Objects.equals(next.paymentId(), value.operationId())
                    || !Objects.equals(next.sourceAccountId(), value.effect().source().accountId())
                    || !Objects.equals(next.destinationAccountId(), value.effect().destination().accountId()))
                return Mono.error(fail(409, "EXACT_PAYMENT_MONETARY_PROOF_REQUIRED"));
            var row = new ManagedPaymentMonetaryProofEntity(new ManagedPaymentIds.ProofId(next.tenant(), effect.id()),
                    next.paymentId(), next.sourceAccountId(), next.destinationAccountId(), effect.participant(),
                    effect.kind().name(), proof.receiptId(), next.version(), effect.fence(), observation.ledgerVersion(),
                    observation.observedAt().atOffset(java.time.ZoneOffset.UTC), observer,
                    new PaymentJson(proof.envelopeJson()), null);
            return journal.insertMonetaryProof(row).then();
        });
    }

}
