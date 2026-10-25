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
import io.fireflysoftware.core.banking.payments.core.services.PaymentOrderChildFilter;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.mappers.core.PaymentFeeMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentFeeDTO;
import io.fireflysoftware.core.banking.payments.models.entities.core.PaymentFee;
import io.fireflysoftware.core.banking.payments.models.repositories.core.PaymentFeeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Service
@Transactional
public class PaymentFeeManagerServiceImpl implements PaymentFeeManagerService {

    @Autowired
    private PaymentFeeRepository repository;

    @Autowired
    private PaymentFeeMapper mapper;

    @Autowired
    private PaymentOrderChildFilter childFilter;

    @Override
    public Mono<PaginationResponse<PaymentFeeDTO>> getAllPaymentFees(UUID paymentOrderId, FilterRequest<PaymentFeeDTO> filterRequest) {
        return childFilter.filter(paymentOrderId, filterRequest, PaymentFee.class, mapper::toDTO);
    }

    @Override
    public Mono<PaymentFeeDTO> createPaymentFee(UUID paymentOrderId, PaymentFeeDTO paymentFeeDTO) {
        paymentFeeDTO.setPaymentOrderId(paymentOrderId);
        PaymentFee entity = mapper.toEntity(paymentFeeDTO);
        entity.setPaymentFeeId(null);
        return repository.save(entity).map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentFeeDTO> getPaymentFeeById(UUID paymentOrderId, UUID paymentFeeId) {
        return repository.findById(paymentFeeId)
                .filter(paymentFee -> paymentFee.getPaymentOrderId().equals(paymentOrderId))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentFeeDTO> updatePaymentFee(UUID paymentOrderId, UUID paymentFeeId, PaymentFeeDTO paymentFeeDTO) {
        return repository.findScopedForUpdate(paymentOrderId, paymentFeeId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment fee not found")))
                .flatMap(existingPaymentFee -> {
                    PaymentFee updatedEntity = mapper.toEntity(paymentFeeDTO);
                    updatedEntity.setPaymentFeeId(existingPaymentFee.getPaymentFeeId());
                    updatedEntity.setPaymentOrderId(existingPaymentFee.getPaymentOrderId());
                    updatedEntity.setDateCreated(existingPaymentFee.getDateCreated());
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> deletePaymentFee(UUID paymentOrderId, UUID paymentFeeId) {
        return repository.findScopedForUpdate(paymentOrderId, paymentFeeId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment fee not found")))
                .flatMap(repository::delete);
    }
}