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


package io.fireflysoftware.core.banking.payments.core.services.core.impl;

import io.fireflysoftware.core.banking.payments.core.services.core.*;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.mappers.core.PaymentScheduleMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentScheduleDTO;
import io.fireflysoftware.core.banking.payments.models.entities.core.PaymentSchedule;
import io.fireflysoftware.core.banking.payments.models.repositories.core.PaymentScheduleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Service
@Transactional
public class PaymentScheduleManagerServiceImpl implements PaymentScheduleManagerService {

    @Autowired
    private PaymentScheduleRepository repository;

    @Autowired
    private PaymentScheduleMapper mapper;

    @Override
    public Mono<PaginationResponse<PaymentScheduleDTO>> getAllPaymentSchedules(UUID paymentOrderId, FilterRequest<PaymentScheduleDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentSchedule.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaymentScheduleDTO> createPaymentSchedule(UUID paymentOrderId, PaymentScheduleDTO paymentScheduleDTO) {
        paymentScheduleDTO.setPaymentOrderId(paymentOrderId);
        PaymentSchedule entity = mapper.toEntity(paymentScheduleDTO);
        return Mono.just(entity)
                .flatMap(repository::save)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentScheduleDTO> getPaymentScheduleById(UUID paymentOrderId, UUID paymentScheduleId) {
        return repository.findById(paymentScheduleId)
                .filter(paymentSchedule -> paymentSchedule.getPaymentOrderId().equals(paymentOrderId))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentScheduleDTO> updatePaymentSchedule(UUID paymentOrderId, UUID paymentScheduleId, PaymentScheduleDTO paymentScheduleDTO) {
        return repository.findById(paymentScheduleId)
                .filter(paymentSchedule -> paymentSchedule.getPaymentOrderId().equals(paymentOrderId))
                .flatMap(existingEntity -> {
                    PaymentSchedule updatedEntity = mapper.toEntity(paymentScheduleDTO);
                    updatedEntity.setPaymentScheduleId(existingEntity.getPaymentScheduleId());
                    updatedEntity.setPaymentOrderId(existingEntity.getPaymentOrderId());
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> deletePaymentSchedule(UUID paymentOrderId, UUID paymentScheduleId) {
        return repository.findById(paymentScheduleId)
                .filter(paymentSchedule -> paymentSchedule.getPaymentOrderId().equals(paymentOrderId))
                .flatMap(repository::delete);
    }
}