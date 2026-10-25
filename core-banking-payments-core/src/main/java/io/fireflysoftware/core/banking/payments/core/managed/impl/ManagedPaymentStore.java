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

import static io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicy.fail;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.*;
import java.util.*;

@Component
public final class ManagedPaymentStore {
    private final ManagedPaymentOperationRepository operations;
    private final ManagedPaymentActionRepository actions;
    private final ManagedPaymentEffectRepository effects;
    private final ManagedPaymentHistoryRepository histories;
    private final ManagedPaymentJournalRepository journal;
    private final ManagedPaymentNativeRepository nativeRepository;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ManagedPaymentStore(ManagedPaymentOperationRepository operations,
            ManagedPaymentActionRepository actions, ManagedPaymentEffectRepository effects,
            ManagedPaymentHistoryRepository histories, ManagedPaymentJournalRepository journal,
            ManagedPaymentNativeRepository nativeRepository) {
        this.operations = operations; this.actions = actions; this.effects = effects;
        this.histories = histories; this.journal = journal; this.nativeRepository = nativeRepository;
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
                null, null, null);
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
            int attempts, String owner, java.time.Instant lease) {}

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
                row.leaseUntil() == null ? null : row.leaseUntil().toInstant());
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
        return requireOne(nativeRepository.deferEffect(tenant, effect.id(), effect.fence(),
                effect.attempts() >= 10 ? "EXHAUSTED" : "PENDING", delay), "PAYMENT_EFFECT_FENCE_CONFLICT");
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
        return requireOne(nativeRepository.resetEffect(tenant, effect.id(), effect.fence()), "PAYMENT_EFFECT_FENCE_CONFLICT");
    }
}
