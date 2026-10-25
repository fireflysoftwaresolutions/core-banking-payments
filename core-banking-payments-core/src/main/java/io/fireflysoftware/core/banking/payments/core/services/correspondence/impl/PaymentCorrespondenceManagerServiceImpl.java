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


package io.fireflysoftware.core.banking.payments.core.services.correspondence.impl;

import io.fireflysoftware.core.banking.payments.core.services.correspondence.*;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.mappers.correspondence.PaymentCorrespondenceMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.correspondence.PaymentCorrespondenceDTO;
import io.fireflysoftware.core.banking.payments.models.entities.correspondence.PaymentCorrespondence;
import io.fireflysoftware.core.banking.payments.models.repositories.correspondence.PaymentCorrespondenceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Service
@Transactional
public class PaymentCorrespondenceManagerServiceImpl implements PaymentCorrespondenceManagerService {

    @Autowired
    private PaymentCorrespondenceRepository repository;

    @Autowired
    private PaymentCorrespondenceMapper mapper;

    @Override
    public Mono<PaginationResponse<PaymentCorrespondenceDTO>> getCorrespondenceByPaymentOrderId(UUID paymentOrderId, FilterRequest<PaymentCorrespondenceDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentCorrespondence.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaginationResponse<PaymentCorrespondenceDTO>> getCorrespondenceByPaymentOrderIdAndType(UUID paymentOrderId, String correspondenceType, FilterRequest<PaymentCorrespondenceDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentCorrespondence.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaymentCorrespondenceDTO> createCorrespondence(UUID paymentOrderId, PaymentCorrespondenceDTO paymentCorrespondenceDTO) {
        paymentCorrespondenceDTO.setPaymentOrderId(paymentOrderId);
        PaymentCorrespondence entity = mapper.toEntity(paymentCorrespondenceDTO);
        return repository.save(entity).map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentCorrespondenceDTO> getCorrespondenceById(UUID paymentCorrespondenceId) {
        return repository.findById(paymentCorrespondenceId)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentCorrespondenceDTO> updateCorrespondence(UUID paymentOrderId, UUID paymentCorrespondenceId, PaymentCorrespondenceDTO paymentCorrespondenceDTO) {
        return repository.findById(paymentCorrespondenceId)
                .flatMap(existingEntity -> {
                    PaymentCorrespondence updatedEntity = mapper.toEntity(paymentCorrespondenceDTO);
                    updatedEntity.setPaymentCorrespondenceId(existingEntity.getPaymentCorrespondenceId());
                    updatedEntity.setPaymentOrderId(paymentOrderId);
                    updatedEntity.setDateCreated(existingEntity.getDateCreated());
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> deleteCorrespondence(UUID paymentCorrespondenceId) {
        return repository.deleteById(paymentCorrespondenceId);
    }
}
