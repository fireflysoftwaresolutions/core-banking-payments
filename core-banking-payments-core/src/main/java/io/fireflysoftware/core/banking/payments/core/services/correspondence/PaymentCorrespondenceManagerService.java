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


package io.fireflysoftware.core.banking.payments.core.services.correspondence;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.correspondence.PaymentCorrespondenceDTO;
import reactor.core.publisher.Mono;

import java.util.UUID;
public interface PaymentCorrespondenceManagerService {
    
    Mono<PaginationResponse<PaymentCorrespondenceDTO>> getCorrespondenceByPaymentOrderId(UUID paymentOrderId, FilterRequest<PaymentCorrespondenceDTO> filterRequest);
    
    Mono<PaginationResponse<PaymentCorrespondenceDTO>> getCorrespondenceByPaymentOrderIdAndType(UUID paymentOrderId, String correspondenceType, FilterRequest<PaymentCorrespondenceDTO> filterRequest);
    
    Mono<PaymentCorrespondenceDTO> createCorrespondence(UUID paymentOrderId, PaymentCorrespondenceDTO paymentCorrespondenceDTO);
    
    Mono<PaymentCorrespondenceDTO> getCorrespondenceById(UUID paymentCorrespondenceId);
    
    Mono<PaymentCorrespondenceDTO> updateCorrespondence(UUID paymentOrderId, UUID paymentCorrespondenceId, PaymentCorrespondenceDTO paymentCorrespondenceDTO);
    
    Mono<Void> deleteCorrespondence(UUID paymentCorrespondenceId);
}
