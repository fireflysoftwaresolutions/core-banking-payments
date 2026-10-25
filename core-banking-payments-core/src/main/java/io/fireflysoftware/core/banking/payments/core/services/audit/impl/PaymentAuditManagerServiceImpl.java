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


package io.fireflysoftware.core.banking.payments.core.services.audit.impl;

import io.fireflysoftware.core.banking.payments.core.services.audit.*;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.mappers.audit.PaymentAuditMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.audit.PaymentAuditDTO;
import io.fireflysoftware.core.banking.payments.models.entities.audit.PaymentAudit;
import io.fireflysoftware.core.banking.payments.models.repositories.audit.PaymentAuditRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Service
@Transactional
public class PaymentAuditManagerServiceImpl implements PaymentAuditManagerService {

    @Autowired
    private PaymentAuditRepository repository;

    @Autowired
    private PaymentAuditMapper mapper;

    @Override
    public Mono<PaginationResponse<PaymentAuditDTO>> getAuditsByPaymentOrderId(UUID paymentOrderId, FilterRequest<PaymentAuditDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentAudit.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaginationResponse<PaymentAuditDTO>> getAuditsByPaymentInstructionId(UUID paymentInstructionId, FilterRequest<PaymentAuditDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentAudit.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaymentAuditDTO> createAudit(PaymentAuditDTO paymentAuditDTO) {
        PaymentAudit entity = mapper.toEntity(paymentAuditDTO);
        return repository.save(entity).map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentAuditDTO> getAuditById(UUID paymentAuditId) {
        return repository.findById(paymentAuditId)
                .map(mapper::toDTO);
    }
}
