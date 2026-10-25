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

package io.fireflysoftware.core.banking.payments.interfaces.dtos.closure;

import java.time.Instant;
import java.util.UUID;

public final class PaymentAccountQuiescenceContracts {
    private PaymentAccountQuiescenceContracts() {}

    public enum FenceState {
        FENCED,
        RELEASED
    }

    public enum ObservationStatus {
        UNKNOWN,
        OBLIGATIONS,
        CLEAR
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            requiredProperties = {"actionId", "closureId", "enrollmentId", "expectedFenceVersion"},
            additionalProperties =
                    io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Quiesce(
            UUID actionId, UUID closureId, UUID enrollmentId, Long expectedFenceVersion) {}

    @io.swagger.v3.oas.annotations.media.Schema(
            requiredProperties = {"actionId", "closureId", "expectedFenceVersion"},
            additionalProperties =
                    io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Release(UUID actionId, UUID closureId, Long expectedFenceVersion) {}

    public record Selection(
            String tenantId,
            UUID accountId,
            UUID enrollmentId,
            UUID contractId,
            String currency,
            Integer currencyScale,
            String currencyPolicy) {}

    public record QuiescenceReceipt(
            UUID fenceId,
            UUID actionId,
            UUID closureId,
            Selection selection,
            long fenceVersion,
            long observedSourceVersion,
            long observedLedgerVersion,
            Instant acceptedAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "PaymentAccountQuiescenceView")
    public record View(
            UUID fenceId,
            UUID closureId,
            Selection selection,
            FenceState state,
            long fenceVersion,
            ObservationStatus observation,
            Long observedSourceVersion,
            UUID observationReceiptId) {}

    public record ReleaseReceipt(
            UUID fenceId,
            UUID actionId,
            UUID closureId,
            Selection selection,
            long fenceVersion,
            Instant releasedAt) {}

    public record ObservationReceipt(
            UUID observationId,
            UUID fenceId,
            UUID closureId,
            Selection selection,
            long fenceVersion,
            long observedSourceVersion,
            ObservationStatus status,
            long total,
            long obligationCount,
            Instant observedAt) {}

    public record Obligation(
            UUID obligationId,
            long ordinal,
            String direction,
            String state,
            String effectState,
            boolean blocking,
            long operationVersion) {}

    public record ObligationPage(
            ObservationReceipt observation, java.util.List<Obligation> items, Long nextAfter) {
        public ObligationPage {
            items = java.util.List.copyOf(items);
        }
    }
}
