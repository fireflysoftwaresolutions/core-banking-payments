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


package io.fireflysoftware.core.banking.payments.core.services.exchange;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.exchange.PaymentExchangeRateDTO;
import reactor.core.publisher.Mono;

import java.util.UUID;
public interface PaymentExchangeRateManagerService {
    
    Mono<PaginationResponse<PaymentExchangeRateDTO>> getExchangeRatesByPaymentOrderId(UUID paymentOrderId, FilterRequest<PaymentExchangeRateDTO> filterRequest);
    
    Mono<PaymentExchangeRateDTO> createExchangeRate(UUID paymentOrderId, PaymentExchangeRateDTO paymentExchangeRateDTO);
    
    Mono<PaymentExchangeRateDTO> getExchangeRateById(UUID paymentOrderId, UUID paymentExchangeRateId);
    
    Mono<PaymentExchangeRateDTO> updateExchangeRate(UUID paymentOrderId, UUID paymentExchangeRateId, PaymentExchangeRateDTO paymentExchangeRateDTO);
    
    Mono<Void> deleteExchangeRate(UUID paymentOrderId, UUID paymentExchangeRateId);
    
    Mono<PaymentExchangeRateDTO> getCurrentExchangeRate(String sourceCurrency, String targetCurrency);
}
