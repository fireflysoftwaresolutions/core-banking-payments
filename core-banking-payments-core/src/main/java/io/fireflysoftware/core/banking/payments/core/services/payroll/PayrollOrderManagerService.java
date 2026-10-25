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


package io.fireflysoftware.core.banking.payments.core.services.payroll;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.payroll.PayrollOrderDTO;
import reactor.core.publisher.Mono;

import java.util.UUID;
public interface PayrollOrderManagerService {

    /**
     * Retrieves a paginated list of payroll orders for a given payment order.
     */
    Mono<PaginationResponse<PayrollOrderDTO>> getAllPayrollOrders(UUID paymentOrderId,
                                                                  FilterRequest<PayrollOrderDTO> filterRequest);

    /**
     * Creates a new payroll order for the specified payment order.
     */
    Mono<PayrollOrderDTO> createPayrollOrder(UUID paymentOrderId, PayrollOrderDTO payrollOrderDTO);

    /**
     * Retrieves a specific payroll order by its unique identifier, within the context of a payment order.
     */
    Mono<PayrollOrderDTO> getPayrollOrderById(UUID paymentOrderId, UUID payrollOrderId);

    /**
     * Updates an existing payroll order by its unique identifier.
     */
    Mono<PayrollOrderDTO> updatePayrollOrder(UUID paymentOrderId, UUID payrollOrderId, PayrollOrderDTO payrollOrderDTO);

    /**
     * Deletes a specific payroll order by its unique identifier, within the context of a payment order.
     */
    Mono<Void> deletePayrollOrder(UUID paymentOrderId, UUID payrollOrderId);
}