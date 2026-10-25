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
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentScheduleDTO;
import reactor.core.publisher.Mono;

import java.util.UUID;
public interface PaymentScheduleManagerService {

    /**
     * Retrieves a paginated list of payment schedules for a given payment order.
     */
    Mono<PaginationResponse<PaymentScheduleDTO>> getAllPaymentSchedules(UUID paymentOrderId,
                                                                        FilterRequest<PaymentScheduleDTO> filterRequest);

    /**
     * Creates a new payment schedule for the specified payment order.
     */
    Mono<PaymentScheduleDTO> createPaymentSchedule(UUID paymentOrderId, PaymentScheduleDTO paymentScheduleDTO);

    /**
     * Retrieves a specific payment schedule by its unique identifier, within the context of a payment order.
     */
    Mono<PaymentScheduleDTO> getPaymentScheduleById(UUID paymentOrderId, UUID paymentScheduleId);

    /**
     * Updates an existing payment schedule by its unique identifier.
     */
    Mono<PaymentScheduleDTO> updatePaymentSchedule(UUID paymentOrderId,
                                                   UUID paymentScheduleId,
                                                   PaymentScheduleDTO paymentScheduleDTO);

    /**
     * Deletes a specific payment schedule by its unique identifier, within the context of a payment order.
     */
    Mono<Void> deletePaymentSchedule(UUID paymentOrderId, UUID paymentScheduleId);
}