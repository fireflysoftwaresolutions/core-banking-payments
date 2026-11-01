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
import static io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentStoreImpl.evolve;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Service("managedPaymentCoordinator")
public class ManagedPaymentCoordinatorServiceImpl implements ManagedPaymentCoordinatorService {
    final ManagedPaymentTransactionsImpl transactions;
    final ManagedPaymentStoreImpl store;
    final int leaseSeconds;

    public ManagedPaymentCoordinatorServiceImpl(
            ManagedPaymentTransactionsImpl transactions,
            @Value("${managed-payments.claim-lease-seconds:30}") int leaseSeconds) {
        this.transactions = transactions;
        this.store = transactions.store;
        if (leaseSeconds < 15 || leaseSeconds > 60)
            throw new IllegalArgumentException("PAYMENT_LEASE_BOUND_15_TO_60_SECONDS_REQUIRED");
        this.leaseSeconds = leaseSeconds;
    }

    static String digest(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            input.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void version(View v, Long expected) {
        if (expected == null || expected < 0) throw fail(400, "EXACT_PAYMENT_VERSION_REQUIRED");
        if (v.version() != expected) throw fail(409, "PAYMENT_VERSION_CONFLICT");
    }

    static void effectAuthority(Call call, String tenant, ManagedPaymentStoreImpl.EffectRow e, View v) {
        String scope =
                switch (e.kind()) {
                    case RESERVE -> "payments:create";
                    case COMMIT -> "payments:confirm";
                    case RELEASE -> "payments:cancel";
                    case OBSERVE -> "payments:recover";
                    case REVERSE -> "payments:reverse";
                };
        require(
                call,
                tenant,
                e.kind() == EffectKind.REVERSE ? scope
                        : call.principal().hasScope(scope) ? scope : "payments:recover",
                v.sourceAccountId(),
                v.destinationAccountId());
        require(
                call,
                tenant,
                switch (e.kind()) {
                    case RESERVE -> "funds:reserve";
                    case COMMIT -> "funds:commit";
                    case RELEASE -> "funds:release";
                    case OBSERVE -> "funds:read";
                    case REVERSE -> "funds:reverse";
                },
                v.sourceAccountId(),
                v.destinationAccountId());
    }

    public Mono<EffectClaim> claim(String tenant, UUID payment, Claim input) {
        return current()
                .flatMap(
                        call -> {
                            require(call, tenant, "payments:coordinate");
                            if (input == null
                                    || input.actionId() == null
                                    || input.expectedVersion() == null
                                    || input.expectedVersion() < 0)
                                return Mono.error(fail(400, "EXACT_PAYMENT_CLAIM_REQUIRED"));
                            String digest =
                                    digest("CLAIM|" + payment + "|" + input.expectedVersion());
                            return transactions.transaction(
                                    call,
                                    tenant,
                                    "MUTATE",
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
                                                            v -> {
                                                                require(
                                                                        call,
                                                                        tenant,
                                                                        "payments:coordinate",
                                                                        v.sourceAccountId(),
                                                                        v.destinationAccountId());
                                                                return store.action(
                                                                                tenant,
                                                                                input.actionId())
                                                                        .flatMap(
                                                                                a -> {
                                                                                    if (!a.payment()
                                                                                                    .equals(
                                                                                                            payment)
                                                                                            || !a.kind()
                                                                                                    .equals(
                                                                                                            "CLAIM")
                                                                                            || !a.digest()
                                                                                                    .equals(
                                                                                                            digest)
                                                                                            || !a.actor()
                                                                                                    .equals(
                                                                                                            call.principal()
                                                                                                                    .subject())
                                                                                            || a
                                                                                                            .receipt()
                                                                                                    == null)
                                                                                        return Mono
                                                                                                .error(
                                                                                                        fail(
                                                                                                                409,
                                                                                                                "PAYMENT_CLAIM_ACTION_CONFLICT"));
                                                                                    var receipt =
                                                                                            store
                                                                                                    .decode(
                                                                                                            a
                                                                                                                    .receipt(),
                                                                                                            EffectClaim
                                                                                                                    .class);
                                                                                    return store.effect(
                                                                                                    tenant,
                                                                                                    receipt
                                                                                                            .effectId())
                                                                                            .map(
                                                                                                    e -> {
                                                                                                        effectAuthority(
                                                                                                                call,
                                                                                                                tenant,
                                                                                                                e,
                                                                                                                v);
                                                                                                        return receipt;
                                                                                                    });
                                                                                })
                                                                        .switchIfEmpty(
                                                                                Mono.defer(
                                                                                        () -> {
                                                                                            version(
                                                                                                    v,
                                                                                                    input
                                                                                                            .expectedVersion());
                                                                                            if (v
                                                                                                            .version()
                                                                                                    >= Long
                                                                                                                    .MAX_VALUE
                                                                                                            - 1)
                                                                                                throw fail(
                                                                                                        409,
                                                                                                        "PAYMENT_RESULT_VERSION_CAPACITY_REQUIRED");
                                                                                            if (v
                                                                                                            .pendingEffectId()
                                                                                                    == null)
                                                                                                return Mono
                                                                                                        .error(
                                                                                                                fail(
                                                                                                                        409,
                                                                                                                        "PAYMENT_HAS_NO_PENDING_EFFECT"));
                                                                                            return store.effect(
                                                                                                            tenant,
                                                                                                            v
                                                                                                                    .pendingEffectId())
                                                                                                    .flatMap(
                                                                                                            e -> {
                                                                                                                effectAuthority(
                                                                                                                        call,
                                                                                                                        tenant,
                                                                                                                        e,
                                                                                                                        v);
                                                                                                                return store.now()
                                                                                                                        .flatMap(
                                                                                                                                now -> {
                                                                                                                                    if (e.state()
                                                                                                                                                    .equals(
                                                                                                                                                            "ACKNOWLEDGED")
                                                                                                                                            || e.state()
                                                                                                                                                    .equals(
                                                                                                                                                            "EXHAUSTED")
                                                                                                                                            || e
                                                                                                                                                                    .lease()
                                                                                                                                                            != null
                                                                                                                                                    && e.lease()
                                                                                                                                                            .isAfter(
                                                                                                                                                                    Instant
                                                                                                                                                                            .ofEpochMilli(
                                                                                                                                                                                    now)))
                                                                                                                                        return Mono
                                                                                                                                                .error(
                                                                                                                                                        fail(
                                                                                                                                                                409,
                                                                                                                                                                "PAYMENT_EFFECT_NOT_CLAIMABLE"));
                                                                                                                                    if (e
                                                                                                                                                            .fence()
                                                                                                                                                    == Long
                                                                                                                                                            .MAX_VALUE
                                                                                                                                            || e
                                                                                                                                                            .attempts()
                                                                                                                                                    == Integer
                                                                                                                                                            .MAX_VALUE)
                                                                                                                                        return Mono
                                                                                                                                                .error(
                                                                                                                                                        fail(
                                                                                                                                                                409,
                                                                                                                                                                "PAYMENT_EFFECT_FENCE_EXHAUSTED"));
                                                                                                                                    var
                                                                                                                                            until =
                                                                                                                                                    Instant
                                                                                                                                                            .ofEpochMilli(
                                                                                                                                                                    now)
                                                                                                                                                            .plusSeconds(
                                                                                                                                                                    leaseSeconds);
                                                                                                                                    State
                                                                                                                                            state =
                                                                                                                                                    switch (e
                                                                                                                                                            .kind()) {
                                                                                                                                                        case RESERVE ->
                                                                                                                                                                State
                                                                                                                                                                        .RESERVING;
                                                                                                                                                        case COMMIT ->
                                                                                                                                                                State
                                                                                                                                                                        .COMMITTING;
                                                                                                                                                        case RELEASE ->
                                                                                                                                                                State
                                                                                                                                                                        .CANCELLING;
                                                                                                                                                        case OBSERVE ->
                                                                                                                                                                v
                                                                                                                                                                        .state();
                                                                                                                                                        case REVERSE -> State.REVERSING;
                                                                                                                                                    };
                                                                                                                                    View
                                                                                                                                            next =
                                                                                                                                                    evolve(
                                                                                                                                                            v,
                                                                                                                                                            state,
                                                                                                                                                            e
                                                                                                                                                                    .id(),
                                                                                                                                                            null);
                                                                                                                                    var
                                                                                                                                            receipt =
                                                                                                                                                    new EffectClaim(
                                                                                                                                                            payment,
                                                                                                                                                            e
                                                                                                                                                                    .id(),
                                                                                                                                                            e
                                                                                                                                                                    .kind(),
                                                                                                                                                            e
                                                                                                                                                                    .participant(),
                                                                                                                                                            e
                                                                                                                                                                            .fence()
                                                                                                                                                                    + 1,
                                                                                                                                                            until,
                                                                                                                                                            next
                                                                                                                                                                    .version(),
                                                                                                                                                            next);
                                                                                                                                    return store.claimEffect(
                                                                                                                                                    tenant,
                                                                                                                                                    e,
                                                                                                                                                    call.principal()
                                                                                                                                                            .subject(),
                                                                                                                                                    until)
                                                                                                                                            .then(
                                                                                                                                                    store
                                                                                                                                                            .insertAction(
                                                                                                                                                                    next,
                                                                                                                                                                    input
                                                                                                                                                                            .actionId(),
                                                                                                                                                                    "CLAIM",
                                                                                                                                                                    digest,
                                                                                                                                                                    call.principal()
                                                                                                                                                                            .subject(),
                                                                                                                                                                    receipt))
                                                                                                                                            .then(
                                                                                                                                                    store
                                                                                                                                                            .save(
                                                                                                                                                                    next,
                                                                                                                                                                    "EFFECT_CLAIMED",
                                                                                                                                                                    call.principal()
                                                                                                                                                                            .subject(),
                                                                                                                                                                    input
                                                                                                                                                                            .actionId(),
                                                                                                                                                                    e
                                                                                                                                                                            .id()))
                                                                                                                                            .thenReturn(
                                                                                                                                                    receipt);
                                                                                                                                });
                                                                                                            });
                                                                                        }));
                                                            }));
                        });
    }

    static void owned(
            ManagedPaymentStoreImpl.EffectRow e,
            View v,
            Call call,
            Long fence,
            Long expected,
            long now) {
        version(v, expected);
        if (fence == null
                || fence < 1
                || !e.id().equals(v.pendingEffectId())
                || e.fence() != fence
                || !"CLAIMED".equals(e.state())
                || !Objects.equals(e.owner(), call.principal().subject())
                || e.lease() == null
                || !e.lease().isAfter(Instant.ofEpochMilli(now)))
            throw fail(409, "CURRENT_PAYMENT_EFFECT_LEASE_REQUIRED");
    }

    public Mono<View> recordFailure(String tenant, UUID payment, Failure input) {
        return current()
                .flatMap(
                        call -> {
                            require(call, tenant, "payments:coordinate");
                            if (input == null
                                    || input.effectId() == null
                                    || input.code() == null
                                    || input.fence() == null
                                    || input.expectedVersion() == null)
                                return Mono.error(
                                        fail(400, "EXACT_PAYMENT_FAILURE_REFERENCE_REQUIRED"));
                            return transactions.transaction(
                                    call,
                                    tenant,
                                    "MUTATE",
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
                                                                                    e -> {
                                                                                        effectAuthority(
                                                                                                call,
                                                                                                tenant,
                                                                                                e,
                                                                                                v);
                                                                                        return store.now()
                                                                                                .flatMap(
                                                                                                        now -> {
                                                                                                            owned(
                                                                                                                    e,
                                                                                                                    v,
                                                                                                                    call,
                                                                                                                    input
                                                                                                                            .fence(),
                                                                                                                    input
                                                                                                                            .expectedVersion(),
                                                                                                                    now);
                                                                                                            View
                                                                                                                    next =
                                                                                                                            evolve(
                                                                                                                                    v,
                                                                                                                                    State
                                                                                                                                            .RECOVERY_REQUIRED,
                                                                                                                                    e
                                                                                                                                            .id(),
                                                                                                                                    e
                                                                                                                                                            .attempts()
                                                                                                                                                    >= 10
                                                                                                                                            ? "RETRY_EXHAUSTED"
                                                                                                                                            : input.code()
                                                                                                                                                    .name());
                                                                                                            return store.deferEffect(
                                                                                                                            tenant,
                                                                                                                            e)
                                                                                                                    .then(
                                                                                                                            store
                                                                                                                                    .save(
                                                                                                                                            next,
                                                                                                                                            "EFFECT_UNCERTAIN",
                                                                                                                                            call.principal()
                                                                                                                                                    .subject(),
                                                                                                                                            null,
                                                                                                                                            e
                                                                                                                                                    .id()))
                                                                                                                    .thenReturn(
                                                                                                                            next);
                                                                                                        });
                                                                                    })));
                        });
    }

    public Mono<RecoveryPage> recoveryQueue(String tenant, UUID after, int limit) {
        return current()
                .flatMap(
                        call -> {
                            require(call, tenant, "payments:recover");
                            require(call, tenant, "payments:coordinate");
                            if (limit < 1 || limit > 100)
                                return Mono.error(fail(400, "BOUNDED_PAYMENT_RECOVERY_REQUIRED"));
                            return transactions.transaction(
                                    call,
                                    tenant,
                                    "READ",
                                    "payments:recover",
                                    accounts(call),
                                    () ->
                                            store.queue(tenant, after, limit + 1)
                                                    .collectList()
                                                    .map(
                                                            rows -> {
                                                                boolean more = rows.size() > limit;
                                                                var selected =
                                                                        java.util.List.copyOf(
                                                                                rows.subList(
                                                                                        0,
                                                                                        Math.min(
                                                                                                limit,
                                                                                                rows
                                                                                                        .size())));
                                                                return new RecoveryPage(
                                                                        selected,
                                                                        more
                                                                                ? selected.getLast()
                                                                                        .paymentId()
                                                                                : null);
                                                            }));
                        });
    }

    static boolean retained(
            ManagedPaymentStoreImpl.RetainedAction action, UUID payment, String kind, String digest) {
        if (!action.payment().equals(payment)
                || !action.kind().equals(kind)
                || !action.digest().equals(digest))
            throw fail(409, "PAYMENT_ACTION_INTENT_CONFLICT");
        return true;
    }

    public Mono<View> decide(String tenant, UUID payment, Decision input) {
        return current()
                .flatMap(
                        call -> {
                            if (input == null
                                    || input.actionId() == null
                                    || input.kind() == null
                                    || input.expectedVersion() == null
                                    || input.expectedVersion() < 0)
                                return Mono.error(fail(400, "EXACT_PAYMENT_DECISION_REQUIRED"));
                            String scope = switch (input.kind()) {
                                case CONFIRM -> "payments:confirm";
                                case CANCEL -> "payments:cancel";
                                case REVERSE -> "payments:reverse";
                            };
                            String funds = switch (input.kind()) {
                                case CONFIRM -> "funds:commit";
                                case CANCEL -> "funds:release";
                                case REVERSE -> "funds:reverse";
                            };
                            require(call, tenant, scope);
                            require(call, tenant, funds);
                            if (input.kind() == DecisionKind.REVERSE)
                                return reverse(call, tenant, payment, input);
                            String kind = input.kind().name();
                            String hash =
                                    digest(kind + "|" + payment + "|" + input.expectedVersion());
                            return transactions.transaction(
                                    call,
                                    tenant,
                                    "MUTATE",
                                    scope,
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
                                                            v -> {
                                                                require(
                                                                        call,
                                                                        tenant,
                                                                        scope,
                                                                        v.sourceAccountId(),
                                                                        v.destinationAccountId());
                                                                require(
                                                                        call,
                                                                        tenant,
                                                                        funds,
                                                                        v.sourceAccountId(),
                                                                        v.destinationAccountId());
                                                                return store.action(
                                                                                tenant,
                                                                                input.actionId())
                                                                        .map(
                                                                                a -> {
                                                                                    retained(
                                                                                            a,
                                                                                            payment,
                                                                                            kind,
                                                                                            hash);
                                                                                    return v;
                                                                                })
                                                                        .switchIfEmpty(
                                                                                Mono.defer(
                                                                                        () -> {
                                                                                            version(
                                                                                                    v,
                                                                                                    input
                                                                                                            .expectedVersion());
                                                                                            if (v
                                                                                                                    .state()
                                                                                                            != State
                                                                                                                    .RESERVED
                                                                                                    || v
                                                                                                                    .pendingEffectId()
                                                                                                            != null
                                                                                                    || !"HELD"
                                                                                                            .equals(
                                                                                                                    v
                                                                                                                            .ledgerState()))
                                                                                                return Mono
                                                                                                        .error(
                                                                                                                fail(
                                                                                                                        409,
                                                                                                                        "CONFIRMED_RESERVATION_DECISION_REQUIRED"));
                                                                                            EffectKind
                                                                                                    effect =
                                                                                                            input
                                                                                                                                    .kind()
                                                                                                                            == DecisionKind
                                                                                                                                    .CONFIRM
                                                                                                                    ? EffectKind
                                                                                                                            .COMMIT
                                                                                                                    : EffectKind
                                                                                                                            .RELEASE;
                                                                                            return store.insertEffect(
                                                                                                            v,
                                                                                                            effect)
                                                                                                    .flatMap(
                                                                                                            e -> {
                                                                                                                View
                                                                                                                        next =
                                                                                                                                evolve(
                                                                                                                                        v,
                                                                                                                                        input
                                                                                                                                                                .kind()
                                                                                                                                                        == DecisionKind
                                                                                                                                                                .CONFIRM
                                                                                                                                                ? State
                                                                                                                                                        .COMMITTING
                                                                                                                                                : State
                                                                                                                                                        .CANCELLING,
                                                                                                                                        e
                                                                                                                                                .id(),
                                                                                                                                        null);
                                                                                                                return store.insertAction(
                                                                                                                                next,
                                                                                                                                input
                                                                                                                                        .actionId(),
                                                                                                                                kind,
                                                                                                                                hash,
                                                                                                                                call.principal()
                                                                                                                                        .subject(),
                                                                                                                                null)
                                                                                                                        .then(
                                                                                                                                store
                                                                                                                                        .save(
                                                                                                                                                next,
                                                                                                                                                "DECISION_"
                                                                                                                                                        + kind,
                                                                                                                                                call.principal()
                                                                                                                                                        .subject(),
                                                                                                                                                input
                                                                                                                                                        .actionId(),
                                                                                                                                                e
                                                                                                                                                        .id()))
                                                                                                                        .thenReturn(
                                                                                                                                next);
                                                                                                            });
                                                                                        }));
                                                            }));
                        });
    }

    record ReversePrepared(View payment, ManagedPaymentStoreImpl.CommitOrigin origin, boolean replay) {}

    private static void reverseAuthority(Call call, String tenant, View payment) {
        for (String scope : Set.of("payments:reverse", "funds:reverse", "funds:read", "funds:reconcile"))
            require(call, tenant, scope, payment.sourceAccountId(), payment.destinationAccountId());
    }

    private static void reversible(View payment, Long expectedVersion) {
        version(payment, expectedVersion);
        if (payment.state() != State.COMPLETED || !"COMMITTED".equals(payment.ledgerState())
                || payment.pendingEffectId() != null || payment.reservationId() == null
                || payment.postingId() == null || payment.reversalId() != null
                || payment.ledgerVersion() == null || payment.ledgerVersion() < 1)
            throw fail(409, "COMPLETED_PAYMENT_COMMIT_REQUIRED");
        if (payment.version() == Long.MAX_VALUE) throw fail(409, "PAYMENT_VERSION_EXHAUSTED");
    }

    private static void originalCommit(View payment, ManagedPaymentStoreImpl.CommitOrigin origin) {
        var e = origin.effect(); var h = origin.history();
        if (e.kind() != EffectKind.COMMIT || !"ACKNOWLEDGED".equals(e.state())
                || !Objects.equals(h.effectId(), e.id()) || !"LEDGER_COMMITTED".equals(h.type())
                || h.state() != State.COMPLETED || h.version() < 0 || h.version() > payment.version()
                || !Objects.equals(h.postingId(), payment.postingId()) || h.reversalId() != null)
            throw fail(409, "RETAINED_PAYMENT_COMMIT_ORIGIN_REQUIRED");
    }

    private Mono<View> reverse(Call call, String tenant, UUID payment, Decision input) {
        require(call, tenant, "funds:read"); require(call, tenant, "funds:reconcile");
        String hash = digest("REVERSE|" + payment + "|" + input.expectedVersion());
        return transactions.transaction(call, tenant, "READ", "payments:reverse", accounts(call),
                () -> store.gate(tenant, payment).then(store.read(tenant, payment)
                        .switchIfEmpty(Mono.error(fail(404, "MANAGED_PAYMENT_NOT_FOUND"))))
                        .flatMap(view -> {
                            reverseAuthority(call, tenant, view);
                            return store.action(tenant, input.actionId()).map(action -> {
                                retained(action, payment, "REVERSE", hash);
                                return new ReversePrepared(view, null, true);
                            }).switchIfEmpty(Mono.defer(() -> {
                                reversible(view, input.expectedVersion());
                                return store.commitOrigin(view).map(origin -> {
                                    originalCommit(view, origin);
                                    return new ReversePrepared(view, origin, false);
                                });
                            }));
                        }))
                .flatMap(prepared -> {
                    if (prepared.replay()) return Mono.just(prepared.payment());
                    var expectation = new LedgerFundsObservationPort.MonetaryExpectation(
                            LedgerFundsObservationPort.MonetaryKind.COMMIT,
                            prepared.origin().effect().participant(), null, null, null, null,
                            prepared.payment().ledgerVersion());
                    return transactions.ledger.observeMonetary(tenant, prepared.payment(), expectation)
                            .onErrorMap(LedgerFundsObservationPort.ObservationUnavailable.class,
                                    error -> fail(503, "VERIFIED_PAYMENT_COMMIT_PROOF_UNAVAILABLE"))
                            .flatMap(observed -> transactions.transaction(call, tenant, "OBSERVE", "payments:reverse", accounts(call),
                                    () -> store.gate(tenant, payment)
                                            .then(store.accountGates(tenant, prepared.payment().sourceAccountId(),
                                                    prepared.payment().destinationAccountId()))
                                            .then(store.read(tenant, payment)
                                                    .switchIfEmpty(Mono.error(fail(404, "MANAGED_PAYMENT_NOT_FOUND"))))
                                            .flatMap(view -> {
                                                reverseAuthority(call, tenant, view);
                                                return store.action(tenant, input.actionId()).map(action -> {
                                                    retained(action, payment, "REVERSE", hash); return view;
                                                }).switchIfEmpty(Mono.defer(() -> {
                                                    reversible(view, input.expectedVersion());
                                                    return store.commitOrigin(view).flatMap(origin -> {
                                                        originalCommit(view, origin);
                                                        if (!origin.equals(prepared.origin()))
                                                            return Mono.error(fail(409, "PAYMENT_COMMIT_ORIGIN_CHANGED"));
                                                        if (observed.commit() == null || observed.reversal() != null
                                                                || !"COMMITTED".equals(observed.funds().ledgerState())
                                                                || !Objects.equals(observed.commit().participantActionId(), origin.effect().participant())
                                                                || !Objects.equals(observed.commit().reservationId(), view.reservationId())
                                                                || !Objects.equals(observed.commit().postingId(), view.postingId()))
                                                            return Mono.error(fail(409, "EXACT_PAYMENT_MONETARY_PROOF_REQUIRED"));
                                                        return store.insertReverseEffect(view, origin).flatMap(effect -> {
                                                            View next = evolve(view, State.REVERSING, effect.id(), null);
                                                            return store.retainMonetaryProof(next, origin.effect(), observed.commit(),
                                                                            observed.funds(), call.principal().subject())
                                                                    .then(store.insertAction(next, input.actionId(), "REVERSE", hash,
                                                                            call.principal().subject(), null))
                                                                    .then(store.save(next, "REVERSE_ACCEPTED", call.principal().subject(),
                                                                            input.actionId(), effect.id())).thenReturn(next);
                                                        });
                                                    });
                                                }));
                                            })));
                });
    }

    public Mono<View> recover(String tenant, UUID payment, Recovery input) {
        return current()
                .flatMap(
                        call -> {
                            require(call, tenant, "payments:recover");
                            if (input == null
                                    || input.actionId() == null
                                    || input.expectedVersion() == null
                                    || input.expectedVersion() < 0)
                                return Mono.error(fail(400, "EXACT_PAYMENT_RECOVERY_REQUIRED"));
                            String hash =
                                    digest("RECOVER|" + payment + "|" + input.expectedVersion());
                            return transactions.transaction(
                                    call,
                                    tenant,
                                    "MUTATE",
                                    "payments:recover",
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
                                                            v -> {
                                                                require(
                                                                        call,
                                                                        tenant,
                                                                        "payments:recover",
                                                                        v.sourceAccountId(),
                                                                        v.destinationAccountId());
                                                                return store.action(
                                                                                tenant,
                                                                                input.actionId())
                                                                        .map(
                                                                                a -> {
                                                                                    retained(
                                                                                            a,
                                                                                            payment,
                                                                                            "RECOVER",
                                                                                            hash);
                                                                                    return v;
                                                                                })
                                                                        .switchIfEmpty(
                                                                                Mono.defer(
                                                                                        () -> {
                                                                                            version(
                                                                                                    v,
                                                                                                    input
                                                                                                            .expectedVersion());
                                                                                            if (v
                                                                                                                    .state()
                                                                                                            == State
                                                                                                                    .CANCELLED
                                                                                                    || v
                                                                                                                    .state()
                                                                                                            == State
                                                                                                                    .EXPIRED
                                                                                                    || v
                                                                                                                    .state()
                                                                                                            == State
                                                                                                                    .REVERSED)
                                                                                                return Mono
                                                                                                        .error(
                                                                                                                fail(
                                                                                                                        409,
                                                                                                                        "PAYMENT_HAS_NO_RECOVERY_WORK"));
                                                                                            Mono<
                                                                                                            ManagedPaymentStoreImpl
                                                                                                                    .EffectRow>
                                                                                                    effect =
                                                                                                            v
                                                                                                                                    .pendingEffectId()
                                                                                                                            == null
                                                                                                                    ? store
                                                                                                                            .observationEffect(
                                                                                                                                    v)
                                                                                                                    : store
                                                                                                                            .effect(
                                                                                                                                    tenant,
                                                                                                                                    v
                                                                                                                                            .pendingEffectId());
                                                                                            return effect
                                                                                                    .flatMap(
                                                                                                            e -> {
                                                                                                                effectAuthority(
                                                                                                                        call,
                                                                                                                        tenant,
                                                                                                                        e,
                                                                                                                        v);
                                                                                                                return store.now()
                                                                                                                        .flatMap(
                                                                                                                                now -> {
                                                                                                                                    if (e
                                                                                                                                                            .lease()
                                                                                                                                                    != null
                                                                                                                                            && e.lease()
                                                                                                                                                    .isAfter(
                                                                                                                                                            Instant
                                                                                                                                                                    .ofEpochMilli(
                                                                                                                                                                            now)))
                                                                                                                                        return Mono
                                                                                                                                                .error(
                                                                                                                                                        fail(
                                                                                                                                                                409,
                                                                                                                                                                "PAYMENT_EFFECT_LEASE_ACTIVE"));
                                                                                                                                    View
                                                                                                                                            next =
                                                                                                                                                    evolve(
                                                                                                                                                            v,
                                                                                                                                                            v
                                                                                                                                                                    .state(),
                                                                                                                                                            e
                                                                                                                                                                    .id(),
                                                                                                                                                            null);
                                                                                                                                    return store.resetEffect(
                                                                                                                                                    tenant,
                                                                                                                                                    e)
                                                                                                                                            .then(
                                                                                                                                                    store
                                                                                                                                                            .insertAction(
                                                                                                                                                                    next,
                                                                                                                                                                    input
                                                                                                                                                                            .actionId(),
                                                                                                                                                                    "RECOVER",
                                                                                                                                                                    hash,
                                                                                                                                                                    call.principal()
                                                                                                                                                                            .subject(),
                                                                                                                                                                    null))
                                                                                                                                            .then(
                                                                                                                                                    store
                                                                                                                                                            .save(
                                                                                                                                                                    next,
                                                                                                                                                                    "RECOVERY_REQUESTED",
                                                                                                                                                                    call.principal()
                                                                                                                                                                            .subject(),
                                                                                                                                                                    input
                                                                                                                                                                            .actionId(),
                                                                                                                                                                    e
                                                                                                                                                                            .id()))
                                                                                                                                            .thenReturn(
                                                                                                                                                    next);
                                                                                                                                });
                                                                                                            });
                                                                                        }));
                                                            }));
                        });
    }
}
