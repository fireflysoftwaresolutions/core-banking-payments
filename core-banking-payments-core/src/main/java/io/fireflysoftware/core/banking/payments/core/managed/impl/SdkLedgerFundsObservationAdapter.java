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

import io.fireflysoftware.core.banking.ledger.sdk.api.LedgerFundsApi;
import io.fireflysoftware.core.banking.ledger.sdk.invoker.ApiClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.*;

@Component
public class SdkLedgerFundsObservationAdapter implements LedgerFundsObservationPort {
    private final PaymentDelegationPort delegation;
    private final String base, audience;
    private final boolean loopback;

    public SdkLedgerFundsObservationAdapter(
            PaymentDelegationPort delegation,
            @Value("${managed-payments.peers.ledger.base-url:}") String base,
            @Value("${managed-payments.peers.ledger.audience:}") String audience,
            @Value("${managed-payments.allow-loopback-http:false}") boolean loopback) {
        this.delegation = delegation;
        this.base = base;
        this.audience = audience;
        this.loopback = loopback;
    }

    Mono<LedgerFundsApi> client(String tenant, UUID source, UUID destination, Set<String> scopes) {
        return client(tenant, Set.of(source, destination), scopes);
    }

    private Mono<LedgerFundsApi> client(String tenant, Set<UUID> resources, Set<String> scopes) {
        return Mono.defer(
                () -> {
                    if (!valid(base, loopback))
                        return Mono.error(fail(503, "CONFIGURED_LEDGER_OBSERVER_REQUIRED"));
                    return delegation
                            .token(audience, tenant, resources, scopes)
                            .timeout(Duration.ofSeconds(4))
                            .map(
                                    token -> {
                                        var web =
                                                ApiClient.buildWebClientBuilder()
                                                        .codecs(
                                                                c ->
                                                                        c.defaultCodecs()
                                                                                .maxInMemorySize(
                                                                                        1024
                                                                                                * 1024))
                                                        .build();
                                        var api = new ApiClient(web).setBasePath(base);
                                        api.addDefaultHeader("Authorization", "Bearer " + token);
                                        return new LedgerFundsApi(api);
                                    });
                });
    }

    @Override
    public Mono<EnrolledAccount> closureAccount(String tenant, UUID account) {
        return client(tenant, Set.of(account), Set.of("funds:read"))
                .flatMap(api -> api.getLedgerFunds(tenant, account, null))
                .switchIfEmpty(Mono.error(fail(503, "LEDGER_CLOSURE_TUPLE_UNAVAILABLE")))
                .map(value -> closureTuple(tenant, account, value))
                .timeout(Duration.ofSeconds(5))
                .onErrorMap(
                        error ->
                                error
                                                instanceof
                                                org.springframework.web.server
                                                        .ResponseStatusException
                                        ? error
                                        : fail(503, "LEDGER_CLOSURE_TUPLE_UNAVAILABLE"));
    }

    static EnrolledAccount closureTuple(
            String tenant,
            UUID account,
            io.fireflysoftware.core.banking.ledger.sdk.model.Account value) {
        if (tenant == null
                || !tenant.matches("[A-Za-z0-9_-]{1,64}")
                || value == null
                || account == null
                || !account.equals(value.getAccountId())
                || value.getEnrollmentId() == null
                || value.getEnrollmentId().equals(new UUID(0, 0))
                || value.getContractId() == null
                || value.getContractId().equals(new UUID(0, 0))
                || value.getCurrency() == null
                || !value.getCurrency().matches("[A-Z]{3}")
                || value.getCurrencyScale() == null
                || value.getCurrencyScale() < 0
                || value.getCurrencyScale() > 18
                || value.getCurrencyPolicy() == null
                || value.getCurrencyPolicy().isBlank()
                || value.getCurrencyPolicy().length() > 100
                || value.getCurrencyPolicy().codePoints().anyMatch(Character::isISOControl)
                || value.getKind() == null
                || !"CUSTOMER".equals(value.getKind().getValue())
                || value.getState() == null
                || value.getVersion() == null
                || value.getVersion() < 0) throw fail(503, "EXACT_LEDGER_CLOSURE_TUPLE_REQUIRED");
        return new EnrolledAccount(
                account,
                value.getEnrollmentId(),
                value.getContractId(),
                value.getCurrency(),
                value.getCurrencyScale(),
                value.getCurrencyPolicy(),
                value.getVersion(),
                value.getState().getValue());
    }

    public Mono<EnrolledPair> accounts(String tenant, UUID source, UUID destination) {
        return client(tenant, source, destination, Set.of("funds:read"))
                .flatMap(
                        api ->
                                Mono.zip(
                                        api.getLedgerFunds(tenant, source, null),
                                        api.getLedgerFunds(tenant, destination, null)))
                .switchIfEmpty(Mono.error(fail(502, "INCOMPLETE_ENROLLED_ACCOUNT_PAIR")))
                .map(
                        pair -> {
                            var a = pair.getT1();
                            var b = pair.getT2();
                            if (!source.equals(a.getAccountId())
                                    || !destination.equals(b.getAccountId())
                                    || a.getKind() == null
                                    || b.getKind() == null
                                    || !"CUSTOMER".equals(a.getKind().getValue())
                                    || !"CUSTOMER".equals(b.getKind().getValue())
                                    || a.getState() == null
                                    || b.getState() == null
                                    || !"ACTIVE".equals(a.getState().getValue())
                                    || !"ACTIVE".equals(b.getState().getValue())
                                    || a.getCurrency() == null
                                    || !a.getCurrency().equals(b.getCurrency())
                                    || a.getCurrencyScale() == null
                                    || !a.getCurrencyScale().equals(b.getCurrencyScale())
                                    || a.getCurrencyPolicy() == null
                                    || a.getCurrencyPolicy().isBlank()
                                    || !a.getCurrencyPolicy().equals(b.getCurrencyPolicy()))
                                throw fail(409, "ACTIVE_CUSTOMER_ACCOUNT_PAIR_REQUIRED");
                            return new EnrolledPair(
                                    source,
                                    destination,
                                    a.getCurrency(),
                                    a.getCurrencyScale(),
                                    a.getCurrencyPolicy());
                        })
                .timeout(Duration.ofSeconds(10))
                .onErrorMap(
                        e ->
                                e instanceof org.springframework.web.server.ResponseStatusException
                                        ? e
                                        : fail(503, "LEDGER_ACCOUNT_OBSERVATION_UNAVAILABLE"));
    }

    public Mono<VerifiedFundsObservation> observe(
            String tenant,
            io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                            .ManagedPaymentContracts.View
                    v) {
        var intent =
                new io.fireflysoftware.core.banking.ledger.sdk.model.Reserve()
                        .operationId(v.paymentId())
                        .sourceAccountId(v.sourceAccountId())
                        .destinationAccountId(v.destinationAccountId())
                        .amount(new java.math.BigDecimal(v.amount()))
                        .currency(v.currency())
                        .ttlSeconds(v.ttlSeconds())
                        .notAfterEpochMillis(v.notAfterEpochMillis());
        return client(
                        tenant,
                        v.sourceAccountId(),
                        v.destinationAccountId(),
                        Set.of("funds:read", "funds:reconcile"))
                .flatMap(api -> api.reconcileFundsReservation(tenant, intent, null))
                .switchIfEmpty(Mono.error(inconsistent()))
                .map(
                        receipt -> {
                            var echoed = receipt.getIntent();
                            if (receipt.getOutcome() == null
                                    || receipt.getObservedAt() == null
                                    || !Objects.equals(
                                            receipt.getNotAfterEpochMillis(),
                                            v.notAfterEpochMillis())
                                    || echoed == null
                                    || !Objects.equals(echoed.getOperationId(), v.paymentId())
                                    || !Objects.equals(
                                            echoed.getSourceAccountId(), v.sourceAccountId())
                                    || !Objects.equals(
                                            echoed.getDestinationAccountId(),
                                            v.destinationAccountId())
                                    || echoed.getAmount() == null
                                    || echoed.getAmount().compareTo(intent.getAmount()) != 0
                                    || !Objects.equals(echoed.getCurrency(), v.currency())
                                    || !Objects.equals(echoed.getTtlSeconds(), v.ttlSeconds())
                                    || !Objects.equals(
                                            echoed.getNotAfterEpochMillis(),
                                            v.notAfterEpochMillis())) throw inconsistent();
                            var at = receipt.getObservedAt().toInstant(java.time.ZoneOffset.UTC);
                            if ("ABSENT_CLOSED".equals(receipt.getOutcome().getValue())) {
                                if (receipt.getReceiptId() == null
                                        || receipt.getReservation() != null
                                        || v.reservationId() != null
                                        || at.toEpochMilli() < v.notAfterEpochMillis()
                                        || v.absenceReceiptId() != null
                                                && !v.absenceReceiptId()
                                                        .equals(receipt.getReceiptId()))
                                    throw inconsistent();
                                return new VerifiedFundsObservation(
                                        "ABSENT_CLOSED",
                                        null,
                                        null,
                                        null,
                                        receipt.getReceiptId(),
                                        null,
                                        at,
                                        null);
                            }
                            var found = receipt.getReservation();
                            if (!"FOUND".equals(receipt.getOutcome().getValue())
                                    || found == null
                                    || receipt.getReceiptId() != null
                                    || v.absenceReceiptId() != null
                                    || found.getReservationId() == null
                                    || found.getState() == null
                                    || found.getVersion() == null
                                    || found.getVersion() < 0
                                    || found.getPostingId() == null
                                    || found.getExpiresAt() == null
                                    || !v.paymentId().equals(found.getOperationId())
                                    || !v.sourceAccountId().equals(found.getSourceAccountId())
                                    || !v.destinationAccountId()
                                            .equals(found.getDestinationAccountId())
                                    || found.getAmount() == null
                                    || found.getAmount().compareTo(intent.getAmount()) != 0
                                    || !v.currency().equals(found.getCurrency()))
                                throw inconsistent();
                            String state = found.getState().getValue();
                            var expiry = found.getExpiresAt().toInstant(java.time.ZoneOffset.UTC);
                            if (expiry.toEpochMilli() > v.notAfterEpochMillis()
                                    || v.reservationId() != null
                                            && !v.reservationId().equals(found.getReservationId())
                                    || v.postingId() != null
                                            && !v.postingId().equals(found.getPostingId())
                                    || v.reservationExpiresAt() != null
                                            && !v.reservationExpiresAt().equals(expiry)
                                    || v.reversalId() != null
                                            && !v.reversalId().equals(found.getReversalId())
                                    || "REVERSED".equals(state)
                                            && (found.getReversalId() == null
                                                    || found.getReversalId()
                                                            .equals(found.getPostingId()))
                                    || !"REVERSED".equals(state) && found.getReversalId() != null)
                                throw inconsistent();
                            if (v.ledgerVersion() != null
                                    && (found.getVersion() < v.ledgerVersion()
                                            || found.getVersion().equals(v.ledgerVersion())
                                                    && !Objects.equals(v.ledgerState(), state)))
                                throw inconsistent();
                            if (v.ledgerState() != null
                                    && !v.ledgerState().equals("HELD")
                                    && !v.ledgerState().equals(state)
                                    && !(v.ledgerState().equals("COMMITTED")
                                            && state.equals("REVERSED"))) throw inconsistent();
                            return new VerifiedFundsObservation(
                                    state,
                                    found.getReservationId(),
                                    found.getPostingId(),
                                    found.getReversalId(),
                                    null,
                                    found.getVersion(),
                                    at,
                                    expiry);
                        })
                .timeout(Duration.ofSeconds(10))
                .onErrorMap(
                        error -> {
                            if (error instanceof ObservationUnavailable
                                    || error
                                            instanceof
                                            org.springframework.web.server.ResponseStatusException)
                                return error;
                            if (error
                                    instanceof
                                    org.springframework.web.reactive.function.client
                                                    .WebClientResponseException
                                            peer) {
                                if (peer.getStatusCode().value() == 409
                                        && peer.getResponseBodyAsString()
                                                .contains("FUNDS_OPERATION_INTENT_CONFLICT"))
                                    return inconsistent();
                                return new ObservationUnavailable(
                                        peer.getStatusCode().is4xxClientError()
                                                ? io.fireflysoftware.core.banking.payments
                                                        .interfaces.dtos.managed
                                                        .ManagedPaymentContracts.FailureCode
                                                        .PEER_REJECTED
                                                : io.fireflysoftware.core.banking.payments
                                                        .interfaces.dtos.managed
                                                        .ManagedPaymentContracts.FailureCode
                                                        .OBSERVATION_UNAVAILABLE);
                            }
                            return new ObservationUnavailable(
                                    io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                                            .ManagedPaymentContracts.FailureCode
                                            .OBSERVATION_UNAVAILABLE);
                        });
    }

    static ObservationUnavailable inconsistent() {
        return new ObservationUnavailable(
                io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                        .ManagedPaymentContracts.FailureCode.INCONSISTENT_OBSERVATION);
    }

    static boolean valid(String value, boolean loopback) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getFragment() == null
                    && ("https".equals(uri.getScheme())
                            || loopback
                                    && "http".equals(uri.getScheme())
                                    && Set.of("127.0.0.1", "localhost", "::1", "[::1]")
                                            .contains(uri.getHost()));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
