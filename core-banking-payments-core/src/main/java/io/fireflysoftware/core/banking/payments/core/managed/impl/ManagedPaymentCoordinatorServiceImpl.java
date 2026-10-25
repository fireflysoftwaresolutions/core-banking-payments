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

import static io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicy.*;
import static io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentStore.evolve;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Service("managedPaymentCoordinator")
public class ManagedPaymentCoordinatorServiceImpl implements ManagedPaymentCoordinatorService {
    final ManagedPaymentTransactions transactions;
    final ManagedPaymentStore store;
    final int leaseSeconds;

    public ManagedPaymentCoordinatorServiceImpl(
            ManagedPaymentTransactions transactions,
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

    static void effectAuthority(Call call, String tenant, ManagedPaymentStore.EffectRow e, View v) {
        String scope =
                switch (e.kind()) {
                    case RESERVE -> "payments:create";
                    case COMMIT -> "payments:confirm";
                    case RELEASE -> "payments:cancel";
                    case OBSERVE -> "payments:recover";
                };
        require(
                call,
                tenant,
                call.principal().hasScope(scope) ? scope : "payments:recover",
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
            ManagedPaymentStore.EffectRow e,
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
            ManagedPaymentStore.RetainedAction action, UUID payment, String kind, String digest) {
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
                            String scope =
                                    input.kind() == DecisionKind.CONFIRM
                                            ? "payments:confirm"
                                            : "payments:cancel";
                            String funds =
                                    input.kind() == DecisionKind.CONFIRM
                                            ? "funds:commit"
                                            : "funds:release";
                            require(call, tenant, scope);
                            require(call, tenant, funds);
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
                                                                                                            ManagedPaymentStore
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
