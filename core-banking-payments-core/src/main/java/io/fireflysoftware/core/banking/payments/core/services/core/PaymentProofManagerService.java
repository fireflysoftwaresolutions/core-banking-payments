/*
 * Copyright 2025 Firefly Software Foundation
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


package io.fireflysoftware.core.banking.payments.core.services.core;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentProofDTO;
import reactor.core.publisher.Mono;

import java.util.UUID;
public interface PaymentProofManagerService {

    /**
     * Retrieves a paginated list of payment proofs for a given payment order.
     */
    Mono<PaginationResponse<PaymentProofDTO>> getAllPaymentProofs(UUID paymentOrderId, FilterRequest<PaymentProofDTO> filterRequest);

    /**
     * Creates a new payment proof record for the specified payment order.
     */
    Mono<PaymentProofDTO> createPaymentProof(UUID paymentOrderId, PaymentProofDTO paymentProofDTO);

    /**
     * Retrieves a specific payment proof by its unique identifier, within the context of a payment order.
     */
    Mono<PaymentProofDTO> getPaymentProofById(UUID paymentOrderId, UUID paymentProofId);

    /**
     * Updates an existing payment proof by its unique identifier.
     */
    Mono<PaymentProofDTO> updatePaymentProof(UUID paymentOrderId, UUID paymentProofId, PaymentProofDTO paymentProofDTO);

    /**
     * Deletes a specific payment proof by its unique identifier, within the context of a payment order.
     */
    Mono<Void> deletePaymentProof(UUID paymentOrderId, UUID paymentProofId);
}