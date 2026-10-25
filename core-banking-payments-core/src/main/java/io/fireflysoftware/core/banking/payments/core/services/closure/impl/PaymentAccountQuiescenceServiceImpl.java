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

package io.fireflysoftware.core.banking.payments.core.services.closure.impl;

import io.fireflysoftware.core.banking.payments.core.services.closure.*;

import static io.fireflysoftware.core.banking.payments.interfaces.dtos.closure.PaymentAccountQuiescenceContracts.*;

import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Supplier;

@Service("paymentAccountQuiescenceService")
public final class PaymentAccountQuiescenceServiceImpl implements PaymentAccountQuiescenceService {
    private final PaymentAccountQuiescenceStore store;
    private final PaymentAccountQuiescenceDatabaseContext context;
    private final PaymentAccountQuiescenceAuthority authority;
    private final LedgerFundsObservationPort ledger;
    private final TransactionalOperator tx;

    public PaymentAccountQuiescenceServiceImpl(
            PaymentAccountQuiescenceStore store,
            PaymentAccountQuiescenceDatabaseContext context,
            PaymentAccountQuiescenceAuthority authority,
            LedgerFundsObservationPort ledger,
            ReactiveTransactionManager manager) {
        this.store = store;
        this.context = context;
        this.authority = authority;
        this.ledger = ledger;
        this.tx = TransactionalOperator.create(manager);
    }

    private <T> Mono<T> transaction(
            PaymentAccountQuiescenceAuthority.Admission admitted, Supplier<Mono<T>> work) {
        return Mono.defer(
                        () ->
                                context.establish(admitted)
                                        .then(
                                                Mono.defer(
                                                        () -> {
                                                            admitted.requireCurrent();
                                                            return work.get();
                                                        })))
                .as(tx::transactional)
                .onErrorMap(
                        e -> {
                            if (e instanceof ResponseStatusException) return e;
                            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                                String message = cause.getMessage();
                                if (cause instanceof io.r2dbc.spi.R2dbcException sql
                                        && "23505".equals(sql.getSqlState())
                                        && message != null
                                        && message.contains("managed_payment_account_action_pkey"))
                                    return conflict();
                                if (message != null
                                        && (message.contains("CONFLICT")
                                                || message.contains("QUIESCED"))) return conflict();
                            }
                            return PaymentAccountQuiescenceStore.unavailable();
                        });
    }

    private Map<String, Object> exact(Selection selection, UUID closure) {
        return Map.of(
                "closureId",
                closure.toString(),
                "enrollmentId",
                selection.enrollmentId().toString(),
                "contractId",
                selection.contractId().toString(),
                "currency",
                selection.currency(),
                "currencyScale",
                selection.currencyScale(),
                "currencyPolicy",
                selection.currencyPolicy());
    }

    private Map<String, Object> command(
            Selection selection, UUID closure, UUID action, long expected, String digest) {
        var values = new HashMap<String, Object>(exact(selection, closure));
        values.put("actionId", action.toString());
        values.put("expectedFenceVersion", expected);
        values.put("intentDigest", digest);
        return Map.copyOf(values);
    }

    public Mono<QuiescenceReceipt> quiesce(String tenant, UUID account, byte[] raw) {
        return PaymentAccountQuiescenceBoundary.outsideTransaction(
                () -> {
                    var input = PaymentAccountQuiescenceRules.decode(raw);
                    return authority
                            .authorize(
                                    tenant,
                                    account,
                                    "quiesce",
                                    Map.of(
                                            "closureId",
                                            input.closureId().toString(),
                                            "enrollmentId",
                                            input.enrollmentId().toString()))
                            .flatMap(
                                    initial ->
                                            transaction(
                                                    initial,
                                                    () ->
                                                            store.action(
                                                                    tenant,
                                                                    account,
                                                                    input.actionId())))
                            .flatMap(
                                    retained -> {
                                        if (!retained.kind().equals("QUIESCE"))
                                            return Mono.error(conflict());
                                        String digest;
                                        try {
                                            digest =
                                                    PaymentAccountQuiescenceCanonical.digest(
                                                            retained.selection(), input);
                                        } catch (RuntimeException e) {
                                            return Mono.error(conflict());
                                        }
                                        if (!digest.equals(retained.digest())
                                                || !input.equals(
                                                        store.decode(
                                                                retained.request(), Quiesce.class)))
                                            return Mono.error(conflict());
                                        var original =
                                                store.decode(
                                                        retained.receipt(),
                                                        QuiescenceReceipt.class);
                                        return authority
                                                .authorize(
                                                        tenant,
                                                        account,
                                                        "quiesce",
                                                        command(
                                                                retained.selection(),
                                                                input.closureId(),
                                                                input.actionId(),
                                                                input.expectedFenceVersion(),
                                                                digest))
                                                .flatMap(
                                                        current ->
                                                                transaction(
                                                                        current,
                                                                        () ->
                                                                                store.quiesce(
                                                                                        retained
                                                                                                .selection(),
                                                                                        input,
                                                                                        original
                                                                                                .observedLedgerVersion(),
                                                                                        digest)));
                                    })
                            .switchIfEmpty(
                                    Mono.defer(
                                            () ->
                                                    ledger.closureAccount(tenant, account)
                                                            .switchIfEmpty(
                                                                    Mono.error(
                                                                            PaymentAccountQuiescenceStore
                                                                                    .unavailable()))
                                                            .flatMap(
                                                                    observed -> {
                                                                        var selected =
                                                                                new Selection(
                                                                                        tenant,
                                                                                        observed
                                                                                                .accountId(),
                                                                                        observed
                                                                                                .enrollmentId(),
                                                                                        observed
                                                                                                .contractId(),
                                                                                        observed
                                                                                                .currency(),
                                                                                        observed
                                                                                                .currencyScale(),
                                                                                        observed
                                                                                                .currencyPolicy());
                                                                        PaymentAccountQuiescenceRules
                                                                                .admit(
                                                                                        selected,
                                                                                        input,
                                                                                        input
                                                                                                .expectedFenceVersion());
                                                                        if ("CLOSED"
                                                                                .equals(
                                                                                        observed
                                                                                                .state()))
                                                                            return Mono.error(
                                                                                    conflict());
                                                                        var digest =
                                                                                PaymentAccountQuiescenceCanonical
                                                                                        .digest(
                                                                                                selected,
                                                                                                input);
                                                                        return authority
                                                                                .authorize(
                                                                                        tenant,
                                                                                        account,
                                                                                        "quiesce",
                                                                                        command(
                                                                                                selected,
                                                                                                input
                                                                                                        .closureId(),
                                                                                                input
                                                                                                        .actionId(),
                                                                                                input
                                                                                                        .expectedFenceVersion(),
                                                                                                digest))
                                                                                .flatMap(
                                                                                        current ->
                                                                                                transaction(
                                                                                                        current,
                                                                                                        () ->
                                                                                                                store
                                                                                                                        .quiesce(
                                                                                                                                selected,
                                                                                                                                input,
                                                                                                                                observed
                                                                                                                                        .ledgerVersion(),
                                                                                                                                digest)));
                                                                    })));
                });
    }

    private Mono<PaymentAccountQuiescenceStore.Fence> retained(
            String tenant, UUID account, UUID closure, String operation) {
        if (!PaymentAccountQuiescenceRules.uuid(closure))
            return Mono.error(PaymentAccountQuiescenceRules.bad());
        return authority
                .authorize(tenant, account, operation, Map.of("closureId", closure.toString()))
                .flatMap(
                        initial ->
                                transaction(initial, () -> store.fence(tenant, account, closure)))
                .switchIfEmpty(
                        Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "PAYMENT_ACCOUNT_QUIESCENCE_NOT_FOUND")));
    }

    public Mono<View> get(String tenant, UUID account, UUID closure) {
        return PaymentAccountQuiescenceBoundary.outsideTransaction(
                () ->
                        retained(tenant, account, closure, "read")
                                .flatMap(
                                        fence ->
                                                authority
                                                        .authorize(
                                                                tenant,
                                                                account,
                                                                "read",
                                                                exact(fence.selection(), closure))
                                                        .flatMap(
                                                                current ->
                                                                        transaction(
                                                                                current,
                                                                                () ->
                                                                                        store.accountGate(
                                                                                                        tenant,
                                                                                                        account)
                                                                                                .then(
                                                                                                        store
                                                                                                                .fence(
                                                                                                                        tenant,
                                                                                                                        account,
                                                                                                                        closure))
                                                                                                .flatMap(
                                                                                                        store
                                                                                                                ::view)
                                                                                                .switchIfEmpty(
                                                                                                        Mono
                                                                                                                .error(
                                                                                                                        PaymentAccountQuiescenceStore
                                                                                                                                .unavailable()))))));
    }

    public Mono<ObligationPage> obligations(
            String tenant, UUID account, UUID closure, UUID observation, long after, int limit) {
        return PaymentAccountQuiescenceBoundary.outsideTransaction(
                () -> {
                    PaymentAccountQuiescenceRules.page(after, limit);
                    if (observation == null && after != 0)
                        return Mono.error(PaymentAccountQuiescenceRules.bad());
                    return retained(tenant, account, closure, "observe")
                            .flatMap(
                                    fence ->
                                            authority
                                                    .authorize(
                                                            tenant,
                                                            account,
                                                            "observe",
                                                            exact(fence.selection(), closure))
                                                    .flatMap(
                                                            current ->
                                                                    transaction(
                                                                            current,
                                                                            () -> {
                                                                                var receipt =
                                                                                        observation
                                                                                                        == null
                                                                                                ? store
                                                                                                        .capture(
                                                                                                                tenant,
                                                                                                                account,
                                                                                                                closure,
                                                                                                                UUID
                                                                                                                        .randomUUID())
                                                                                                : store
                                                                                                        .observation(
                                                                                                                tenant,
                                                                                                                account,
                                                                                                                observation);
                                                                                return store.accountGate(
                                                                                                tenant,
                                                                                                account)
                                                                                        .then(
                                                                                                receipt)
                                                                                        .switchIfEmpty(
                                                                                                Mono
                                                                                                        .error(
                                                                                                                new ResponseStatusException(
                                                                                                                        HttpStatus
                                                                                                                                .NOT_FOUND,
                                                                                                                        "RETAINED_PAYMENT_OBSERVATION_NOT_FOUND")))
                                                                                        .flatMap(
                                                                                                value -> {
                                                                                                    if (!value.fenceId()
                                                                                                                    .equals(
                                                                                                                            fence
                                                                                                                                    .fenceId())
                                                                                                            || !value.selection()
                                                                                                                    .equals(
                                                                                                                            fence
                                                                                                                                    .selection()))
                                                                                                        return Mono
                                                                                                                .error(
                                                                                                                        conflict());
                                                                                                    return store
                                                                                                            .page(
                                                                                                                    value,
                                                                                                                    after,
                                                                                                                    limit);
                                                                                                });
                                                                            })));
                });
    }

    public Mono<ReleaseReceipt> release(String tenant, UUID account, byte[] raw) {
        return PaymentAccountQuiescenceBoundary.outsideTransaction(
                () -> {
                    var input = PaymentAccountQuiescenceRules.decodeRelease(raw);
                    return retained(tenant, account, input.closureId(), "release")
                            .flatMap(
                                    fence ->
                                            authority
                                                    .authorize(
                                                            tenant,
                                                            account,
                                                            "release",
                                                            command(
                                                                    fence.selection(),
                                                                    input.closureId(),
                                                                    input.actionId(),
                                                                    input.expectedFenceVersion(),
                                                                    PaymentAccountQuiescenceCanonical
                                                                            .releaseDigest(
                                                                                    fence
                                                                                            .selection(),
                                                                                    input)))
                                                    .flatMap(
                                                            current ->
                                                                    transaction(
                                                                            current,
                                                                            () ->
                                                                                    store.release(
                                                                                            tenant,
                                                                                            account,
                                                                                            input,
                                                                                            PaymentAccountQuiescenceCanonical
                                                                                                    .releaseDigest(
                                                                                                            fence
                                                                                                                    .selection(),
                                                                                                            input)))));
                });
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "PAYMENT_ACCOUNT_QUIESCENCE_INTENT_OR_VERSION_CONFLICT");
    }
}
