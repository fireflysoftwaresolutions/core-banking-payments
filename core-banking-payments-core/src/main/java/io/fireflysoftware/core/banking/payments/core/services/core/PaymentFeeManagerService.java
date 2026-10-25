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
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentFeeDTO;
import reactor.core.publisher.Mono;

import java.util.UUID;
/**
 * Service interface for managing payment fees in a manager context.
 */
public interface PaymentFeeManagerService {

    /**
     * Retrieves a paginated list of payment fees for a given payment order.
     */
    Mono<PaginationResponse<PaymentFeeDTO>> getAllPaymentFees(UUID paymentOrderId, FilterRequest<PaymentFeeDTO> filterRequest);

    /**
     * Creates a new payment fee for the specified payment order.
     */
    Mono<PaymentFeeDTO> createPaymentFee(UUID paymentOrderId, PaymentFeeDTO paymentFeeDTO);

    /**
     * Retrieves a specific payment fee by its unique identifier, within the context of a payment order.
     */
    Mono<PaymentFeeDTO> getPaymentFeeById(UUID paymentOrderId, UUID paymentFeeId);

    /**
     * Updates an existing payment fee by its unique identifier.
     */
    Mono<PaymentFeeDTO> updatePaymentFee(UUID paymentOrderId, UUID paymentFeeId, PaymentFeeDTO paymentFeeDTO);

    /**
     * Deletes a specific payment fee by its unique identifier, within the context of a payment order.
     */
    Mono<Void> deletePaymentFee(UUID paymentOrderId, UUID paymentFeeId);
}