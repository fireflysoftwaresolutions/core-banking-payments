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


package io.fireflysoftware.core.banking.payments.core.services.exchange.impl;

import io.fireflysoftware.core.banking.payments.core.services.exchange.*;

import org.fireflyframework.core.filters.FilterRequest;
import io.fireflysoftware.core.banking.payments.core.services.PaymentOrderChildFilter;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.mappers.exchange.PaymentExchangeRateMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.exchange.PaymentExchangeRateDTO;
import io.fireflysoftware.core.banking.payments.models.entities.exchange.PaymentExchangeRate;
import io.fireflysoftware.core.banking.payments.models.repositories.exchange.PaymentExchangeRateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@Transactional
public class PaymentExchangeRateManagerServiceImpl implements PaymentExchangeRateManagerService {

    @Autowired
    private PaymentExchangeRateRepository repository;

    @Autowired
    private PaymentExchangeRateMapper mapper;

    @Autowired
    private PaymentOrderChildFilter childFilter;

    @Override
    public Mono<PaginationResponse<PaymentExchangeRateDTO>> getExchangeRatesByPaymentOrderId(UUID paymentOrderId, FilterRequest<PaymentExchangeRateDTO> filterRequest) {
        return childFilter.filter(paymentOrderId, filterRequest, PaymentExchangeRate.class, mapper::toDTO);
    }

    @Override
    public Mono<PaymentExchangeRateDTO> createExchangeRate(UUID paymentOrderId, PaymentExchangeRateDTO paymentExchangeRateDTO) {
        paymentExchangeRateDTO.setPaymentOrderId(paymentOrderId);
        PaymentExchangeRate entity = mapper.toEntity(paymentExchangeRateDTO);
        entity.setPaymentExchangeRateId(null);
        return repository.save(entity).map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentExchangeRateDTO> getExchangeRateById(UUID paymentOrderId, UUID paymentExchangeRateId) {
        return repository.findByPaymentExchangeRateIdAndPaymentOrderId(paymentExchangeRateId, paymentOrderId)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PaymentExchangeRateDTO> updateExchangeRate(UUID paymentOrderId, UUID paymentExchangeRateId, PaymentExchangeRateDTO paymentExchangeRateDTO) {
        return repository.findScopedForUpdate(paymentOrderId, paymentExchangeRateId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Exchange rate not found")))
                .flatMap(existingEntity -> {
                    PaymentExchangeRate updatedEntity = mapper.toEntity(paymentExchangeRateDTO);
                    updatedEntity.setPaymentExchangeRateId(existingEntity.getPaymentExchangeRateId());
                    updatedEntity.setPaymentOrderId(existingEntity.getPaymentOrderId());
                    updatedEntity.setDateCreated(existingEntity.getDateCreated());
                    return repository.save(updatedEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> deleteExchangeRate(UUID paymentOrderId, UUID paymentExchangeRateId) {
        return repository.findScopedForUpdate(paymentOrderId, paymentExchangeRateId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Exchange rate not found")))
                .flatMap(repository::delete);
    }

    @Override
    public Mono<PaymentExchangeRateDTO> getCurrentExchangeRate(String sourceCurrency, String targetCurrency) {
        return repository.findFirstBySourceCurrencyAndTargetCurrencyOrderByRateDateDesc(sourceCurrency, targetCurrency)
                .map(mapper::toDTO);
    }
}
