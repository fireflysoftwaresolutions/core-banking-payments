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
import io.fireflysoftware.core.banking.payments.core.mappers.core.PaymentOrderMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentOrderDTO;
import io.fireflysoftware.core.banking.payments.models.entities.core.PaymentOrder;
import io.fireflysoftware.core.banking.payments.models.repositories.core.PaymentOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Service
@Transactional
public class PaymentOrderManagerServiceImpl implements PaymentOrderManagerService {

    @Autowired
    private PaymentOrderRepository repository;

    @Autowired
    private PaymentOrderMapper mapper;

    @Override
    public Mono<PaginationResponse<PaymentOrderDTO>> getAllPaymentOrders(FilterRequest<PaymentOrderDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PaymentOrder.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PaymentOrderDTO> createPaymentOrder(PaymentOrderDTO paymentOrderDTO) {
        return Mono.just(paymentOrderDTO)
                .map(mapper::toEntity)
                .flatMap(repository::save)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentOrderDTO> getPaymentOrderById(UUID paymentOrderId) {
        return repository.findById(paymentOrderId)
                .map(mapper::toDTO)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment order not found.")));
    }

    @Override
    public Mono<PaymentOrderDTO> updatePaymentOrder(UUID paymentOrderId, PaymentOrderDTO paymentOrderDTO) {
        return repository.findById(paymentOrderId)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment order not found.")))
                .flatMap(existingOrder -> {
                    paymentOrderDTO.setPaymentOrderId(paymentOrderId);
                    return Mono.just(paymentOrderDTO)
                            .map(mapper::toEntity)
                            .flatMap(repository::save)
                            .map(mapper::toDTO);
                });
    }

    @Override
    public Mono<Void> deletePaymentOrder(UUID paymentOrderId) {
        return repository.findById(paymentOrderId)
                .switchIfEmpty(Mono.error(new RuntimeException("Payment order not found.")))
                .flatMap(repository::delete)
                .then();
    }
}