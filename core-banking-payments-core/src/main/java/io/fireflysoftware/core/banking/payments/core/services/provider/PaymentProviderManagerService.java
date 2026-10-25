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


package io.fireflysoftware.core.banking.payments.core.services.provider;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.provider.PaymentProviderDTO;
import reactor.core.publisher.Mono;

import java.util.UUID;
public interface PaymentProviderManagerService {

    /**
     * Retrieves a paginated list of payment providers for a given payment order.
     */
    Mono<PaginationResponse<PaymentProviderDTO>> getAllPaymentProviders(UUID paymentOrderId,
                                                                        FilterRequest<PaymentProviderDTO> filterRequest);

    /**
     * Creates a new payment provider for the specified payment order.
     */
    Mono<PaymentProviderDTO> createPaymentProvider(UUID paymentOrderId, PaymentProviderDTO paymentProviderDTO);

    /**
     * Retrieves a specific payment provider by its unique identifier, within the context of a payment order.
     */
    Mono<PaymentProviderDTO> getPaymentProviderById(UUID paymentOrderId, UUID paymentProviderId);

    /**
     * Updates an existing payment provider by its unique identifier.
     */
    Mono<PaymentProviderDTO> updatePaymentProvider(UUID paymentOrderId, UUID paymentProviderId,
                                                   PaymentProviderDTO paymentProviderDTO);

    /**
     * Deletes a specific payment provider by its unique identifier, within the context of a payment order.
     */
    Mono<Void> deletePaymentProvider(UUID paymentOrderId, UUID paymentProviderId);
}
