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

package io.fireflysoftware.core.banking.payments.core.managed;

import io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicy;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface LedgerFundsObservationPort {
    record EnrolledPair(
            UUID sourceAccountId,
            UUID destinationAccountId,
            String currency,
            int currencyScale,
            String currencyPolicy) {}

    Mono<EnrolledPair> accounts(String tenant, UUID source, UUID destination);

    record EnrolledAccount(
            UUID accountId,
            UUID enrollmentId,
            UUID contractId,
            String currency,
            int currencyScale,
            String currencyPolicy,
            long ledgerVersion,
            String state) {}

    default Mono<EnrolledAccount> closureAccount(String tenant, UUID account) {
        return Mono.error(
                ManagedPaymentPolicy.fail(503, "QUALIFIED_CLOSURE_ACCOUNT_OBSERVER_REQUIRED"));
    }

    record VerifiedFundsObservation(
            String ledgerState,
            UUID reservationId,
            UUID postingId,
            UUID reversalId,
            UUID absenceReceiptId,
            Long ledgerVersion,
            java.time.Instant observedAt,
            java.time.Instant expiresAt) {}

    final class ObservationUnavailable extends RuntimeException {
        private final io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                        .ManagedPaymentContracts.FailureCode
                code;

        public ObservationUnavailable(
                io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                                .ManagedPaymentContracts.FailureCode
                        code) {
            super(code.name());
            this.code = code;
        }

        public io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                        .ManagedPaymentContracts.FailureCode
                code() {
            return code;
        }
    }

    Mono<VerifiedFundsObservation> observe(
            String tenant,
            io.fireflysoftware.core.banking.payments.interfaces.dtos.managed
                            .ManagedPaymentContracts.View
                    intent);
}
