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

import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.EnrolledPair;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.*;

import java.util.*;
import java.util.function.Supplier;

@Service("managedPaymentService")
public class ManagedPaymentServiceImpl implements ManagedPaymentService {
    private final ManagedPaymentTransactions transactions;
    final ManagedPaymentStore store;
    final LedgerFundsObservationPort ledger;
    final ManagedPaymentDatabaseContext context;
    final TransactionalOperator tx;

    public ManagedPaymentServiceImpl(ManagedPaymentTransactions transactions) {
        this.transactions = transactions; this.store = transactions.store; this.ledger = transactions.ledger;
        this.context = transactions.context; this.tx = transactions.tx;
    }

    public Mono<Accepted> create(String tenant, Create input) {
        return current()
                .flatMap(
                        call -> {
                            ManagedPaymentIntent.basic(input);
                            require(
                                    call,
                                    tenant,
                                    "payments:create",
                                    input.sourceAccountId(),
                                    input.destinationAccountId());
                            require(
                                    call,
                                    tenant,
                                    "funds:read",
                                    input.sourceAccountId(),
                                    input.destinationAccountId());
                            UUID[] pair = {input.sourceAccountId(), input.destinationAccountId()};
                            return transactions.transaction(
                                            call,
                                            tenant,
                                            "CREATE",
                                            pair,
                                            () ->
                                                    store.byAction(tenant, input.actionId())
                                                            .map(v -> replay(v, input)))
                                    .switchIfEmpty(
                                            Mono.defer(
                                                    () ->
                                                            ledger.accounts(
                                                                            tenant, pair[0],
                                                                            pair[1])
                                                                    .flatMap(
                                                                            enrolled -> {
                                                                                var intent =
                                                                                        ManagedPaymentIntent
                                                                                                .canonical(
                                                                                                        input,
                                                                                                        enrolled);
                                                                                return transactions.transaction(
                                                                                        call,
                                                                                        tenant,
                                                                                        "CREATE",
                                                                                        pair,
                                                                                        () ->
                                                                                                store.gate(
                                                                                                                tenant,
                                                                                                                input
                                                                                                                        .actionId())
                                                                                                        .then(
                                                                                                                store.byAction(
                                                                                                                                tenant,
                                                                                                                                input
                                                                                                                                        .actionId())
                                                                                                                        .map(
                                                                                                                                v ->
                                                                                                                                        replay(
                                                                                                                                                v,
                                                                                                                                                input)))
                                                                                                        .switchIfEmpty(
                                                                                                                Mono
                                                                                                                        .defer(
                                                                                                                                () ->
                                                                                                                                        store.accountGates(
                                                                                                                                                        tenant,
                                                                                                                                                        pair)
                                                                                                                                                .then(
                                                                                                                                                        store
                                                                                                                                                                .now())
                                                                                                                                                .flatMap(
                                                                                                                                                        now -> {
                                                                                                                                                            long
                                                                                                                                                                    deadline =
                                                                                                                                                                            Math
                                                                                                                                                                                    .addExact(
                                                                                                                                                                                            now,
                                                                                                                                                                                            input
                                                                                                                                                                                                            .ttlSeconds()
                                                                                                                                                                                                    * 1000L);
                                                                                                                                                            if (input
                                                                                                                                                                            .customerNotAfterEpochMillis()
                                                                                                                                                                    != null)
                                                                                                                                                                deadline =
                                                                                                                                                                        Math
                                                                                                                                                                                .min(
                                                                                                                                                                                        deadline,
                                                                                                                                                                                        input
                                                                                                                                                                                                .customerNotAfterEpochMillis());
                                                                                                                                                            if (deadline
                                                                                                                                                                    <= now)
                                                                                                                                                                return Mono
                                                                                                                                                                        .error(
                                                                                                                                                                                fail(
                                                                                                                                                                                        400,
                                                                                                                                                                                        "PAYMENT_DEADLINE_ALREADY_ELAPSED"));
                                                                                                                                                            UUID
                                                                                                                                                                    payment =
                                                                                                                                                                            UUID
                                                                                                                                                                                    .randomUUID(),
                                                                                                                                                                    effect =
                                                                                                                                                                            UUID
                                                                                                                                                                                    .randomUUID();
                                                                                                                                                            var
                                                                                                                                                                    v =
                                                                                                                                                                            new View(
                                                                                                                                                                                    payment,
                                                                                                                                                                                    input
                                                                                                                                                                                            .actionId(),
                                                                                                                                                                                    tenant,
                                                                                                                                                                                    pair[
                                                                                                                                                                                            0],
                                                                                                                                                                                    pair[
                                                                                                                                                                                            1],
                                                                                                                                                                                    intent
                                                                                                                                                                                            .fixedScaleAmount(),
                                                                                                                                                                                    intent.normalizedRequest()
                                                                                                                                                                                            .currency(),
                                                                                                                                                                                    intent
                                                                                                                                                                                            .currencyScale(),
                                                                                                                                                                                    intent
                                                                                                                                                                                            .currencyPolicy(),
                                                                                                                                                                                    input
                                                                                                                                                                                            .ttlSeconds(),
                                                                                                                                                                                    input
                                                                                                                                                                                            .customerNotAfterEpochMillis(),
                                                                                                                                                                                    deadline,
                                                                                                                                                                                    call.principal()
                                                                                                                                                                                            .subject(),
                                                                                                                                                                                    State
                                                                                                                                                                                            .RECEIVED,
                                                                                                                                                                                    0,
                                                                                                                                                                                    null,
                                                                                                                                                                                    null,
                                                                                                                                                                                    null,
                                                                                                                                                                                    null,
                                                                                                                                                                                    null,
                                                                                                                                                                                    null,
                                                                                                                                                                                    effect,
                                                                                                                                                                                    null,
                                                                                                                                                                                    null,
                                                                                                                                                                                    null);
                                                                                                                                                            return store.create(
                                                                                                                                                                            v,
                                                                                                                                                                            intent
                                                                                                                                                                                    .requestDigest(),
                                                                                                                                                                            effect)
                                                                                                                                                                    .thenReturn(
                                                                                                                                                                            new Accepted(
                                                                                                                                                                                    v,
                                                                                                                                                                                    false));
                                                                                                                                                        }))));
                                                                            })));
                        });
    }

    Accepted replay(View v, Create input) {
        try {
            var candidate =
                    ManagedPaymentIntent.canonical(
                            input,
                            new EnrolledPair(
                                    v.sourceAccountId(),
                                    v.destinationAccountId(),
                                    v.currency(),
                                    v.currencyScale(),
                                    v.currencyPolicy()));
            var retained =
                    new Create(
                            v.createActionId(),
                            v.sourceAccountId(),
                            v.destinationAccountId(),
                            new java.math.BigDecimal(v.amount()),
                            v.currency(),
                            v.ttlSeconds(),
                            v.customerNotAfterEpochMillis());
            var digest =
                    ManagedPaymentIntent.canonical(
                                    retained,
                                    new EnrolledPair(
                                            v.sourceAccountId(),
                                            v.destinationAccountId(),
                                            v.currency(),
                                            v.currencyScale(),
                                            v.currencyPolicy()))
                            .requestDigest();
            if (!candidate.requestDigest().equals(digest))
                throw fail(409, "PAYMENT_ACTION_INTENT_CONFLICT");
            return new Accepted(v, true);
        } catch (org.springframework.web.server.ResponseStatusException mismatch) {
            throw fail(409, "PAYMENT_ACTION_INTENT_CONFLICT");
        }
    }

    public Mono<View> read(String tenant, UUID id) {
        return current()
                .flatMap(
                        call -> {
                            require(call, tenant, "payments:read");
                            return transactions.transaction(
                                    call,
                                    tenant,
                                    "READ",
                                    accounts(call),
                                    () ->
                                            store.read(tenant, id)
                                                    .switchIfEmpty(
                                                            Mono.error(
                                                                    fail(
                                                                            404,
                                                                            "MANAGED_PAYMENT_NOT_FOUND"))));
                        });
    }

    public Flux<HistoryEvent> history(String tenant, UUID id, long after, int limit) {
        if (after < -1 || limit < 1 || limit > 100)
            return Flux.error(fail(400, "BOUNDED_PAYMENT_HISTORY_REQUIRED"));
        return read(tenant, id)
                .flatMapMany(
                        v ->
                                current()
                                        .flatMapMany(
                                                call ->
                                                        context.establish(
                                                                        call,
                                                                        tenant,
                                                                        "READ",
                                                                        v.sourceAccountId(),
                                                                        v.destinationAccountId())
                                                                .thenMany(
                                                                        store.history(
                                                                                tenant, id, after,
                                                                                limit))
                                                                .as(tx::transactional)));
    }
}
