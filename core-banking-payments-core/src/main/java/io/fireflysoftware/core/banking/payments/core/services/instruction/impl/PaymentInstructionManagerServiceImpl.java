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


package io.fireflysoftware.core.banking.payments.core.services.instruction.impl;

import io.fireflysoftware.core.banking.payments.core.services.instruction.*;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.mappers.instruction.PaymentInstructionMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.instruction.PaymentInstructionDTO;
import io.fireflysoftware.core.banking.payments.models.entities.instruction.PaymentInstruction;
import io.fireflysoftware.core.banking.payments.models.repositories.instruction.PaymentInstructionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Service
@Transactional
public class PaymentInstructionManagerServiceImpl implements PaymentInstructionManagerService {

    @Autowired
    private PaymentInstructionRepository repository;

    @Autowired
    private PaymentInstructionMapper mapper;

    @Override
    public Mono<PaginationResponse<PaymentInstructionDTO>> getAllPaymentInstructions(UUID paymentOrderId, FilterRequest<PaymentInstructionDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentInstruction.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaymentInstructionDTO> createPaymentInstruction(UUID paymentOrderId, PaymentInstructionDTO paymentInstructionDTO) {
        PaymentInstruction paymentInstruction = mapper.toEntity(paymentInstructionDTO);
        paymentInstruction.setPaymentOrderId(paymentOrderId);
        return repository.save(paymentInstruction)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentInstructionDTO> getPaymentInstructionById(UUID paymentOrderId, UUID paymentInstructionId) {
        return repository.findById(paymentInstructionId)
                .filter(paymentInstruction -> paymentInstruction.getPaymentOrderId().equals(paymentOrderId))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentInstructionDTO> updatePaymentInstruction(UUID paymentOrderId, UUID paymentInstructionId, PaymentInstructionDTO paymentInstructionDTO) {
        return repository.findById(paymentInstructionId)
                .filter(paymentInstruction -> paymentInstruction.getPaymentOrderId().equals(paymentOrderId))
                .flatMap(existing -> {
                    existing.setInstructionId(paymentInstructionDTO.getInstructionId());
                    existing.setInstructionType(paymentInstructionDTO.getInstructionType().name());
                    existing.setInstructionDate(paymentInstructionDTO.getInstructionDate());
                    existing.setInstructionStatus(paymentInstructionDTO.getInstructionStatus());
                    return repository.save(existing);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> deletePaymentInstruction(UUID paymentOrderId, UUID paymentInstructionId) {
        return repository.findById(paymentInstructionId)
                .filter(paymentInstruction -> paymentInstruction.getPaymentOrderId().equals(paymentOrderId))
                .flatMap(repository::delete);
    }
}