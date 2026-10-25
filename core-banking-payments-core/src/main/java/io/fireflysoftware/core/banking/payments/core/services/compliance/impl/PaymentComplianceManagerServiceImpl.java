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


package io.fireflysoftware.core.banking.payments.core.services.compliance.impl;

import io.fireflysoftware.core.banking.payments.core.services.compliance.*;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.mappers.compliance.PaymentComplianceMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.compliance.PaymentComplianceDTO;
import io.fireflysoftware.core.banking.payments.models.entities.compliance.PaymentCompliance;
import io.fireflysoftware.core.banking.payments.models.repositories.compliance.PaymentComplianceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.time.LocalDateTime;

@Service
@Transactional
public class PaymentComplianceManagerServiceImpl implements PaymentComplianceManagerService {

    @Autowired
    private PaymentComplianceRepository repository;

    @Autowired
    private PaymentComplianceMapper mapper;

    @Override
    public Mono<PaginationResponse<PaymentComplianceDTO>> getComplianceByPaymentOrderId(UUID paymentOrderId, FilterRequest<PaymentComplianceDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentCompliance.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaymentComplianceDTO> createCompliance(UUID paymentOrderId, PaymentComplianceDTO paymentComplianceDTO) {
        paymentComplianceDTO.setPaymentOrderId(paymentOrderId);
        PaymentCompliance entity = mapper.toEntity(paymentComplianceDTO);
        return repository.save(entity).map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentComplianceDTO> getComplianceById(UUID paymentComplianceId) {
        return repository.findById(paymentComplianceId)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentComplianceDTO> updateCompliance(UUID paymentOrderId, UUID paymentComplianceId, PaymentComplianceDTO paymentComplianceDTO) {
        return repository.findById(paymentComplianceId)
                .flatMap(existingEntity -> {
                    PaymentCompliance updatedEntity = mapper.toEntity(paymentComplianceDTO);
                    updatedEntity.setPaymentComplianceId(existingEntity.getPaymentComplianceId());
                    updatedEntity.setPaymentOrderId(paymentOrderId);
                    updatedEntity.setDateCreated(existingEntity.getDateCreated());
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentComplianceDTO> approveCompliance(UUID paymentOrderId, String approvedBy, String complianceNotes) {
        return repository.findFirstByPaymentOrderIdOrderByDateCreatedDesc(paymentOrderId)
                .flatMap(existingEntity -> {
                    existingEntity.setApprovedBy(approvedBy);
                    existingEntity.setComplianceNotes(complianceNotes);
                    existingEntity.setApprovalDate(LocalDateTime.now());
                    return repository.save(existingEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentComplianceDTO> rejectCompliance(UUID paymentOrderId, String rejectionReason) {
        return repository.findFirstByPaymentOrderIdOrderByDateCreatedDesc(paymentOrderId)
                .flatMap(existingEntity -> {
                    existingEntity.setRejectionReason(rejectionReason);
                    return repository.save(existingEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> deleteCompliance(UUID paymentComplianceId) {
        return repository.deleteById(paymentComplianceId);
    }
}
