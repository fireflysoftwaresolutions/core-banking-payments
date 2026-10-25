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


package io.fireflysoftware.core.banking.payments.core.services.beneficiary.impl;

import io.fireflysoftware.core.banking.payments.core.services.beneficiary.*;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.mappers.beneficiary.PaymentBeneficiaryMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.beneficiary.PaymentBeneficiaryDTO;
import io.fireflysoftware.core.banking.payments.models.entities.beneficiary.PaymentBeneficiary;
import io.fireflysoftware.core.banking.payments.models.repositories.beneficiary.PaymentBeneficiaryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Service
@Transactional
public class PaymentBeneficiaryManagerServiceImpl implements PaymentBeneficiaryManagerService {

    @Autowired
    private PaymentBeneficiaryRepository repository;

    @Autowired
    private PaymentBeneficiaryMapper mapper;

    @Override
    public Mono<PaginationResponse<PaymentBeneficiaryDTO>> getAllBeneficiaries(FilterRequest<PaymentBeneficiaryDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentBeneficiary.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaginationResponse<PaymentBeneficiaryDTO>> getBeneficiariesByPayerAccountId(UUID payerAccountId, FilterRequest<PaymentBeneficiaryDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentBeneficiary.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaginationResponse<PaymentBeneficiaryDTO>> getFavoriteBeneficiariesByPayerAccountId(UUID payerAccountId, FilterRequest<PaymentBeneficiaryDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentBeneficiary.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaymentBeneficiaryDTO> createBeneficiary(PaymentBeneficiaryDTO paymentBeneficiaryDTO) {
        PaymentBeneficiary entity = mapper.toEntity(paymentBeneficiaryDTO);
        return repository.save(entity).map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentBeneficiaryDTO> getBeneficiaryById(UUID paymentBeneficiaryId) {
        return repository.findById(paymentBeneficiaryId)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentBeneficiaryDTO> updateBeneficiary(UUID paymentBeneficiaryId, PaymentBeneficiaryDTO paymentBeneficiaryDTO) {
        return repository.findById(paymentBeneficiaryId)
                .flatMap(existingEntity -> {
                    PaymentBeneficiary updatedEntity = mapper.toEntity(paymentBeneficiaryDTO);
                    updatedEntity.setPaymentBeneficiaryId(existingEntity.getPaymentBeneficiaryId());
                    updatedEntity.setDateCreated(existingEntity.getDateCreated());
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentBeneficiaryDTO> markAsFavorite(UUID paymentBeneficiaryId, Boolean isFavorite) {
        return repository.findById(paymentBeneficiaryId)
                .flatMap(existingEntity -> {
                    existingEntity.setIsFavorite(isFavorite);
                    return repository.save(existingEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> deleteBeneficiary(UUID paymentBeneficiaryId) {
        return repository.deleteById(paymentBeneficiaryId);
    }
}
