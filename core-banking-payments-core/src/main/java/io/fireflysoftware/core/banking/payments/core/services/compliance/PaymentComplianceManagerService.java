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


package io.fireflysoftware.core.banking.payments.core.services.compliance;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.compliance.PaymentComplianceDTO;
import reactor.core.publisher.Mono;

import java.util.UUID;
public interface PaymentComplianceManagerService {
    
    Mono<PaginationResponse<PaymentComplianceDTO>> getComplianceByPaymentOrderId(UUID paymentOrderId, FilterRequest<PaymentComplianceDTO> filterRequest);
    
    Mono<PaymentComplianceDTO> createCompliance(UUID paymentOrderId, PaymentComplianceDTO paymentComplianceDTO);
    
    Mono<PaymentComplianceDTO> getComplianceById(UUID paymentComplianceId);
    
    Mono<PaymentComplianceDTO> updateCompliance(UUID paymentOrderId, UUID paymentComplianceId, PaymentComplianceDTO paymentComplianceDTO);
    
    Mono<PaymentComplianceDTO> approveCompliance(UUID paymentOrderId, String approvedBy, String complianceNotes);
    
    Mono<PaymentComplianceDTO> rejectCompliance(UUID paymentOrderId, String rejectionReason);
    
    Mono<Void> deleteCompliance(UUID paymentComplianceId);
}
