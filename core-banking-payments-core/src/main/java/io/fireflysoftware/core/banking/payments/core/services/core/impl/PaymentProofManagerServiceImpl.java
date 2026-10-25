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
import io.fireflysoftware.core.banking.payments.core.mappers.core.PaymentProofMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentProofDTO;
import io.fireflysoftware.core.banking.payments.models.entities.core.PaymentProof;
import io.fireflysoftware.core.banking.payments.models.repositories.core.PaymentProofRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Service
@Transactional
public class PaymentProofManagerServiceImpl implements PaymentProofManagerService {

    @Autowired
    private PaymentProofRepository repository;

    @Autowired
    private PaymentProofMapper mapper;

    @Override
    public Mono<PaginationResponse<PaymentProofDTO>> getAllPaymentProofs(UUID paymentOrderId, FilterRequest<PaymentProofDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentProof.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaymentProofDTO> createPaymentProof(UUID paymentOrderId, PaymentProofDTO paymentProofDTO) {
        PaymentProof paymentProof = mapper.toEntity(paymentProofDTO);
        paymentProof.setPaymentOrderId(paymentOrderId);
        return Mono.just(paymentProof)
                .flatMap(repository::save)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentProofDTO> getPaymentProofById(UUID paymentOrderId, UUID paymentProofId) {
        return repository.findById(paymentProofId)
                .filter(entity -> entity.getPaymentOrderId().equals(paymentOrderId))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentProofDTO> updatePaymentProof(UUID paymentOrderId, UUID paymentProofId, PaymentProofDTO paymentProofDTO) {
        return repository.findById(paymentProofId)
                .filter(entity -> entity.getPaymentOrderId().equals(paymentOrderId))
                .flatMap(existingProof -> {
                    PaymentProof updatedProof = mapper.toEntity(paymentProofDTO);
                    updatedProof.setPaymentProofId(existingProof.getPaymentProofId());
                    updatedProof.setPaymentOrderId(existingProof.getPaymentOrderId());
                    return repository.save(updatedProof);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> deletePaymentProof(UUID paymentOrderId, UUID paymentProofId) {
        return repository.findById(paymentProofId)
                .filter(entity -> entity.getPaymentOrderId().equals(paymentOrderId))
                .flatMap(repository::delete);
    }
}
