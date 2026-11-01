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

import static io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicyImpl.*;

import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.util.UUID;

@Service("managedPaymentObservationService")
public class ManagedPaymentObservationServiceImpl implements ManagedPaymentObservationService {
    private final ManagedPaymentTransactionsImpl transactions;
    private final ManagedPaymentCoordinatorService coordinator;
    private final ManagedPaymentStoreImpl store;
    private final LedgerFundsObservationPort ledger;

    public ManagedPaymentObservationServiceImpl(
            ManagedPaymentTransactionsImpl transactions,
            ManagedPaymentCoordinatorService coordinator,
            LedgerFundsObservationPort ledger) {
        this.transactions = transactions;
        this.coordinator = coordinator;
        this.store = transactions.store;
        this.ledger = ledger;
    }

    record Prepared(View payment, ManagedPaymentStoreImpl.EffectRow effect) {}

    record Observation(VerifiedFundsObservation receipt, FailureCode failure) {}

    public Mono<View> acknowledge(String tenant, UUID payment, Acknowledge input) {
        return current()
                .flatMap(
                        call -> {
                            require(call, tenant, "payments:coordinate");
                            require(call, tenant, "funds:read");
                            require(call, tenant, "funds:reconcile");
                            if (input == null
                                    || input.effectId() == null
                                    || input.fence() == null
                                    || input.fence() < 1
                                    || input.expectedVersion() == null
                                    || input.expectedVersion() < 0)
                                return Mono.error(
                                        fail(400, "EXACT_PAYMENT_ACKNOWLEDGMENT_REQUIRED"));
                            return transactions.transaction(
                                            call,
                                            tenant,
                                            "READ",
                                            "payments:coordinate",
                                            accounts(call),
                                            () ->
                                                    store.gate(tenant, payment)
                                                            .then(
                                                                    store.read(tenant, payment)
                                                                            .switchIfEmpty(
                                                                                    Mono.error(
                                                                                            fail(
                                                                                                    404,
                                                                                                    "MANAGED_PAYMENT_NOT_FOUND"))))
                                                            .flatMap(
                                                                    v ->
                                                                            store.effect(
                                                                                            tenant,
                                                                                            input
                                                                                                    .effectId())
                                                                                    .flatMap(
                                                                                            e ->
                                                                                                    store.now()
                                                                                                            .map(
                                                                                                                    now -> {
                                                                                                                        ManagedPaymentCoordinatorServiceImpl
                                                                                                                                .effectAuthority(
                                                                                                                                        call,
                                                                                                                                        tenant,
                                                                                                                                        e,
                                                                                                                                        v);
                                                                                                                        require(
                                                                                                                                call,
                                                                                                                                tenant,
                                                                                                                                "funds:read",
                                                                                                                                v
                                                                                                                                        .sourceAccountId(),
                                                                                                                                v
                                                                                                                                        .destinationAccountId());
                                                                                                                        require(
                                                                                                                                call,
                                                                                                                                tenant,
                                                                                                                                "funds:reconcile",
                                                                                                                                v
                                                                                                                                        .sourceAccountId(),
                                                                                                                                v
                                                                                                                                        .destinationAccountId());
                                                                                                                        ManagedPaymentCoordinatorServiceImpl
                                                                                                                                .owned(
                                                                                                                                        e,
                                                                                                                                        v,
                                                                                                                                        call,
                                                                                                                                        input
                                                                                                                                                .fence(),
                                                                                                                                        input
                                                                                                                                                .expectedVersion(),
                                                                                                                                        now);
                                                                                                                        return new Prepared(
                                                                                                                                v,
                                                                                                                                e);
                                                                                                                    }))))
                                    .flatMap(
                                            prepared ->
                                                    prepared.effect().kind() == EffectKind.REVERSE
                                                            ? acknowledgeReverse(call, tenant, payment, input, prepared)
                                                            : ledger.observe(tenant, prepared.payment())
                                                            .map(
                                                                    observed ->
                                                                            new Observation(
                                                                                    observed, null))
                                                            .onErrorResume(
                                                                    ObservationUnavailable.class,
                                                                    e ->
                                                                            Mono.just(
                                                                                    new Observation(
                                                                                            null,
                                                                                            e
                                                                                                    .code())))
                                                            .flatMap(
                                                                    result -> {
                                                                        if (result.failure()
                                                                                != null)
                                                                            return coordinator
                                                                                    .recordFailure(
                                                                                            tenant,
                                                                                            payment,
                                                                                            new Failure(
                                                                                                    input
                                                                                                            .effectId(),
                                                                                                    input
                                                                                                            .fence(),
                                                                                                    input
                                                                                                            .expectedVersion(),
                                                                                                    result
                                                                                                            .failure()));
                                                                        var found =
                                                                                result.receipt();
                                                                        if ("REVERSED".equals(found.ledgerState()))
                                                                            return coordinator.recordFailure(
                                                                                    tenant, payment,
                                                                                    new Failure(input.effectId(), input.fence(),
                                                                                            input.expectedVersion(),
                                                                                            FailureCode.INCONSISTENT_OBSERVATION));
                                                                        if ("HELD"
                                                                                        .equals(
                                                                                                found
                                                                                                        .ledgerState())
                                                                                && (prepared.effect()
                                                                                                        .kind()
                                                                                                == EffectKind
                                                                                                        .COMMIT
                                                                                        || prepared.effect()
                                                                                                        .kind()
                                                                                                == EffectKind
                                                                                                        .RELEASE))
                                                                            return coordinator
                                                                                    .recordFailure(
                                                                                            tenant,
                                                                                            payment,
                                                                                            new Failure(
                                                                                                    input
                                                                                                            .effectId(),
                                                                                                    input
                                                                                                            .fence(),
                                                                                                    input
                                                                                                            .expectedVersion(),
                                                                                                    FailureCode
                                                                                                            .OBSERVATION_UNAVAILABLE));
                                                                        return transactions.transaction(
                                                                                call,
                                                                                tenant,
                                                                                "OBSERVE",
                                                                                "payments:coordinate",
                                                                                accounts(call),
                                                                                () ->
                                                                                        store.gate(
                                                                                                        tenant,
                                                                                                        payment)
                                                                                                .then(
                                                                                                        store.read(
                                                                                                                        tenant,
                                                                                                                        payment)
                                                                                                                .switchIfEmpty(
                                                                                                                        Mono
                                                                                                                                .error(
                                                                                                                                        fail(
                                                                                                                                                404,
                                                                                                                                                "MANAGED_PAYMENT_NOT_FOUND"))))
                                                                                                .flatMap(
                                                                                                        v ->
                                                                                                                store.effect(
                                                                                                                                tenant,
                                                                                                                                input
                                                                                                                                        .effectId())
                                                                                                                        .flatMap(
                                                                                                                                e ->
                                                                                                                                        store.now()
                                                                                                                                                .flatMap(
                                                                                                                                                        now -> {
                                                                                                                                                            ManagedPaymentCoordinatorServiceImpl
                                                                                                                                                                    .effectAuthority(
                                                                                                                                                                            call,
                                                                                                                                                                            tenant,
                                                                                                                                                                            e,
                                                                                                                                                                            v);
                                                                                                                                                            require(
                                                                                                                                                                    call,
                                                                                                                                                                    tenant,
                                                                                                                                                                    "funds:read",
                                                                                                                                                                    v
                                                                                                                                                                            .sourceAccountId(),
                                                                                                                                                                    v
                                                                                                                                                                            .destinationAccountId());
                                                                                                                                                            require(
                                                                                                                                                                    call,
                                                                                                                                                                    tenant,
                                                                                                                                                                    "funds:reconcile",
                                                                                                                                                                    v
                                                                                                                                                                            .sourceAccountId(),
                                                                                                                                                                    v
                                                                                                                                                                            .destinationAccountId());
                                                                                                                                                            ManagedPaymentCoordinatorServiceImpl
                                                                                                                                                                    .owned(
                                                                                                                                                                            e,
                                                                                                                                                                            v,
                                                                                                                                                                            call,
                                                                                                                                                                            input
                                                                                                                                                                                    .fence(),
                                                                                                                                                                            input
                                                                                                                                                                                    .expectedVersion(),
                                                                                                                                                                            now);
                                                                                                                                                            State
                                                                                                                                                                    state =
                                                                                                                                                                            switch (found
                                                                                                                                                                                    .ledgerState()) {
                                                                                                                                                                                case "HELD" ->
                                                                                                                                                                                        State
                                                                                                                                                                                                .RESERVED;
                                                                                                                                                                                case "COMMITTED" ->
                                                                                                                                                                                        State
                                                                                                                                                                                                .COMPLETED;
                                                                                                                                                                                case "REVERSED" ->
                                                                                                                                                                                        State
                                                                                                                                                                                                .REVERSED;
                                                                                                                                                                                case "RELEASED" ->
                                                                                                                                                                                        State
                                                                                                                                                                                                .CANCELLED;
                                                                                                                                                                                case "EXPIRED",
                                                                                                                                                                                        "ABSENT_CLOSED" ->
                                                                                                                                                                                        State
                                                                                                                                                                                                .EXPIRED;
                                                                                                                                                                                default ->
                                                                                                                                                                                        throw fail(
                                                                                                                                                                                                503,
                                                                                                                                                                                                "VERIFIED_LEDGER_STATE_REQUIRED");
                                                                                                                                                                            };
                                                                                                                                                            if (v
                                                                                                                                                                            .version()
                                                                                                                                                                    == Long
                                                                                                                                                                            .MAX_VALUE)
                                                                                                                                                                return Mono
                                                                                                                                                                        .error(
                                                                                                                                                                                fail(
                                                                                                                                                                                        409,
                                                                                                                                                                                        "PAYMENT_VERSION_EXHAUSTED"));
                                                                                                                                                            View
                                                                                                                                                                    next =
                                                                                                                                                                            new View(
                                                                                                                                                                                    v
                                                                                                                                                                                            .paymentId(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .createActionId(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .tenant(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .sourceAccountId(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .destinationAccountId(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .amount(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .currency(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .currencyScale(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .currencyPolicy(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .ttlSeconds(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .customerNotAfterEpochMillis(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .notAfterEpochMillis(),
                                                                                                                                                                                    v
                                                                                                                                                                                            .initiator(),
                                                                                                                                                                                    state,
                                                                                                                                                                                    v
                                                                                                                                                                                                    .version()
                                                                                                                                                                                            + 1,
                                                                                                                                                                                    found
                                                                                                                                                                                            .reservationId(),
                                                                                                                                                                                    found
                                                                                                                                                                                            .postingId(),
                                                                                                                                                                                    found
                                                                                                                                                                                            .reversalId(),
                                                                                                                                                                                    found
                                                                                                                                                                                            .absenceReceiptId(),
                                                                                                                                                                                    found
                                                                                                                                                                                            .ledgerVersion(),
                                                                                                                                                                                    found
                                                                                                                                                                                            .observedAt(),
                                                                                                                                                                                    null,
                                                                                                                                                                                    null,
                                                                                                                                                                                    found
                                                                                                                                                                                            .ledgerState(),
                                                                                                                                                                                    found
                                                                                                                                                                                            .expiresAt());
                                                                                                                                                            return store.acknowledgeEffect(
                                                                                                                                                                            tenant,
                                                                                                                                                                            e)
                                                                                                                                                                    .then(
                                                                                                                                                                            store
                                                                                                                                                                                    .save(
                                                                                                                                                                                            next,
                                                                                                                                                                                            "LEDGER_"
                                                                                                                                                                                                    + found
                                                                                                                                                                                                            .ledgerState(),
                                                                                                                                                                                            call.principal()
                                                                                                                                                                                                    .subject(),
                                                                                                                                                                                            null,
                                                                                                                                                                                            e
                                                                                                                                                                                                    .id()))
                                                                                                                                                                    .thenReturn(
                                                                                                                                                                            next);
                                                                                                                                                        }))));
                                                                    }));
                        });
    }
    record ReversePrepared(Prepared payment, MonetaryExpectation expectation,
            io.fireflysoftware.core.banking.payments.models.entities.managed.ManagedPaymentMonetaryProofEntity original) {}
    record MonetaryObservation(VerifiedMonetaryObservation receipt, FailureCode failure) {}

    private ReversePrepared reverseOrigin(Prepared prepared, ManagedPaymentStoreImpl.CommitOrigin origin,
            io.fireflysoftware.core.banking.payments.models.entities.managed.ManagedPaymentMonetaryProofEntity proof) {
        var v = prepared.payment(); var e = prepared.effect();
        if (e.originalEffectId() == null || e.originalCommitVersion() == null || e.originalReservationId() == null
                || !e.originalEffectId().equals(origin.effect().id())
                || !e.originalCommitVersion().equals(origin.history().version())
                || !e.originalReservationId().equals(v.reservationId())
                || !"COMMIT".equals(proof.kind()) || !proof.id().tenantId().equals(v.tenant())
                || !proof.id().effectId().equals(origin.effect().id()) || !proof.paymentId().equals(v.paymentId())
                || !proof.sourceAccountId().equals(v.sourceAccountId()) || !proof.destinationAccountId().equals(v.destinationAccountId())
                || !proof.participantActionId().equals(origin.effect().participant())
                || proof.retainedVersion() == null || proof.retainedVersion() > v.version()
                || proof.observedReservationVersion() == null || proof.observedReservationVersion() < 1)
            throw fail(409, "RETAINED_PAYMENT_MONETARY_ORIGIN_REQUIRED");
        io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.MonetaryReceiptEnvelope envelope;
        try {
            envelope = store.decode(proof.receiptEnvelope().value(),
                    io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.MonetaryReceiptEnvelope.class);
            io.fireflysoftware.core.banking.ledger.interfaces.protocol.receipt.FundsReceiptCodec.verify(envelope);
        } catch (RuntimeException invalid) {
            throw fail(503, "RETAINED_PAYMENT_MONETARY_PROOF_INVALID");
        }
        var value = envelope.value();
        if (value.effectKind() != io.fireflysoftware.core.banking.ledger.interfaces.dtos.funds.FundsReceiptContracts.EffectKind.COMMIT
                || !value.receiptId().equals(proof.receiptId())
                || !value.actionId().equals(origin.effect().participant()) || !value.operationId().equals(v.paymentId())
                || !value.tenant().equals(v.tenant()) || !value.reservationId().equals(v.reservationId())
                || !value.postingId().equals(v.postingId()))
            throw fail(409, "RETAINED_PAYMENT_MONETARY_ORIGIN_REQUIRED");
        long minimumOwner = Math.max(proof.observedReservationVersion(), v.ledgerVersion() == null ? 0 : v.ledgerVersion());
        return new ReversePrepared(prepared, new MonetaryExpectation(MonetaryKind.REVERSE, e.participant(),
                value.actionId(), value.receiptId(), envelope.canonical().digest(), value.postingId(), minimumOwner), proof);
    }

    private Mono<View> acknowledgeReverse(ManagedPaymentPolicyImpl.Call call, String tenant, UUID payment,
            Acknowledge input, Prepared first) {
        return transactions.transaction(call, tenant, "READ", "payments:coordinate", accounts(call),
                () -> store.gate(tenant, payment).then(store.read(tenant, payment)
                        .switchIfEmpty(Mono.error(fail(404, "MANAGED_PAYMENT_NOT_FOUND"))))
                        .flatMap(view -> store.effect(tenant, input.effectId()).flatMap(effect -> store.now().flatMap(now -> {
                            ManagedPaymentCoordinatorServiceImpl.effectAuthority(call, tenant, effect, view);
                            ManagedPaymentCoordinatorServiceImpl.owned(effect, view, call, input.fence(), input.expectedVersion(), now);
                            if (effect.kind() != EffectKind.REVERSE || !effect.equals(first.effect()))
                                return Mono.error(fail(409, "PAYMENT_REVERSE_EFFECT_CHANGED"));
                            return store.commitOrigin(view).flatMap(origin -> store.monetaryProof(tenant, origin.effect().id())
                                    .switchIfEmpty(Mono.error(fail(409, "RETAINED_PAYMENT_MONETARY_ORIGIN_REQUIRED")))
                                    .map(proof -> reverseOrigin(new Prepared(view, effect), origin, proof)));
                        }))))
                .flatMap(prepared -> ledger.observeMonetary(tenant, prepared.payment().payment(), prepared.expectation())
                        .map(receipt -> new MonetaryObservation(receipt, null))
                        .onErrorResume(ObservationUnavailable.class, error -> Mono.just(new MonetaryObservation(null, error.code())))
                        .flatMap(observation -> {
                            if (observation.failure() != null)
                                return coordinator.recordFailure(tenant, payment, new Failure(input.effectId(), input.fence(),
                                        input.expectedVersion(), observation.failure()));
                            var receipt = observation.receipt();
                            if (receipt == null || receipt.reversal() == null || receipt.commit() == null
                                    || !"REVERSED".equals(receipt.funds().ledgerState())
                                    || !prepared.expectation().selectedActionId().equals(receipt.reversal().participantActionId())
                                    || !prepared.expectation().originalCommitReceiptId().equals(receipt.reversal().originalCommitReceiptId())
                                    || !prepared.expectation().originalPostingId().equals(receipt.reversal().originalPostingId())
                                    || !prepared.expectation().originalCommitDigest().equals(receipt.commit().digest()))
                                return coordinator.recordFailure(tenant, payment, new Failure(input.effectId(), input.fence(),
                                        input.expectedVersion(), FailureCode.INCONSISTENT_OBSERVATION));
                            return transactions.transaction(call, tenant, "OBSERVE", "payments:coordinate", accounts(call),
                                    () -> store.gate(tenant, payment)
                                            .then(store.accountGates(tenant, prepared.payment().payment().sourceAccountId(),
                                                    prepared.payment().payment().destinationAccountId()))
                                            .then(store.read(tenant, payment)
                                                    .switchIfEmpty(Mono.error(fail(404, "MANAGED_PAYMENT_NOT_FOUND"))))
                                            .flatMap(view -> store.effect(tenant, input.effectId()).flatMap(effect -> store.now().flatMap(now -> {
                                                ManagedPaymentCoordinatorServiceImpl.effectAuthority(call, tenant, effect, view);
                                                require(call, tenant, "funds:read", view.sourceAccountId(), view.destinationAccountId());
                                                require(call, tenant, "funds:reconcile", view.sourceAccountId(), view.destinationAccountId());
                                                ManagedPaymentCoordinatorServiceImpl.owned(effect, view, call, input.fence(), input.expectedVersion(), now);
                                                if (!effect.equals(prepared.payment().effect()))
                                                    return Mono.error(fail(409, "PAYMENT_REVERSE_EFFECT_CHANGED"));
                                                return store.monetaryProof(tenant, effect.originalEffectId())
                                                        .filter(prepared.original()::equals)
                                                        .switchIfEmpty(Mono.error(fail(409, "PAYMENT_COMMIT_ORIGIN_CHANGED")))
                                                        .flatMap(original -> {
                                                            View next = reversed(view, receipt.funds());
                                                            return store.retainMonetaryProof(next, effect, receipt.reversal(), receipt.funds(), call.principal().subject())
                                                                    .then(store.acknowledgeEffect(tenant, effect))
                                                                    .then(store.save(next, "LEDGER_REVERSED", call.principal().subject(), null, effect.id()))
                                                                    .thenReturn(next);
                                                        });
                                            }))));
                        }));
    }

    private static View reversed(View v, VerifiedFundsObservation found) {
        if (v.version() == Long.MAX_VALUE) throw fail(409, "PAYMENT_VERSION_EXHAUSTED");
        return new View(v.paymentId(), v.createActionId(), v.tenant(), v.sourceAccountId(), v.destinationAccountId(),
                v.amount(), v.currency(), v.currencyScale(), v.currencyPolicy(), v.ttlSeconds(),
                v.customerNotAfterEpochMillis(), v.notAfterEpochMillis(), v.initiator(), State.REVERSED, v.version() + 1,
                found.reservationId(), found.postingId(), found.reversalId(), found.absenceReceiptId(), found.ledgerVersion(),
                found.observedAt(), null, null, found.ledgerState(), found.expiresAt());
    }

}
