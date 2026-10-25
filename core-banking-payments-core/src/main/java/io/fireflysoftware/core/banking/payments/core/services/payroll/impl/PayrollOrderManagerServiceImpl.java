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


package io.fireflysoftware.core.banking.payments.core.services.payroll.impl;

import io.fireflysoftware.core.banking.payments.core.services.payroll.*;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.mappers.payroll.PayrollOrderMapper;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.payroll.PayrollOrderDTO;
import io.fireflysoftware.core.banking.payments.models.entities.payroll.PayrollOrder;
import io.fireflysoftware.core.banking.payments.models.repositories.payroll.PayrollOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Service
@Transactional
public class PayrollOrderManagerServiceImpl implements PayrollOrderManagerService {

    @Autowired
    private PayrollOrderRepository repository;

    @Autowired
    private PayrollOrderMapper mapper;

    @Override
    public Mono<PaginationResponse<PayrollOrderDTO>> getAllPayrollOrders(UUID paymentOrderId, FilterRequest<PayrollOrderDTO> filterRequest) {
        return FilterUtils
                .createFilter(
                        PayrollOrder.class,
                        mapper::toDTO
                )
                .filter(filterRequest);
    }

    @Override
    public Mono<PayrollOrderDTO> createPayrollOrder(UUID paymentOrderId, PayrollOrderDTO payrollOrderDTO) {
        payrollOrderDTO.setPaymentOrderId(paymentOrderId);
        PayrollOrder payrollOrder = mapper.toEntity(payrollOrderDTO);
        return Mono.just(payrollOrder)
                .flatMap(repository::save)
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PayrollOrderDTO> getPayrollOrderById(UUID paymentOrderId, UUID payrollOrderId) {
        return repository.findById(payrollOrderId)
                .filter(order -> order.getPaymentOrderId().equals(paymentOrderId))
                .map(mapper::toDTO);
    }

    @Override
    public Mono<PayrollOrderDTO> updatePayrollOrder(UUID paymentOrderId, UUID payrollOrderId, PayrollOrderDTO payrollOrderDTO) {
        return repository.findById(payrollOrderId)
                .filter(order -> order.getPaymentOrderId().equals(paymentOrderId))
                .flatMap(existingOrder -> {
                    PayrollOrder updatedOrder = mapper.toEntity(payrollOrderDTO);
                    updatedOrder.setPayrollOrderId(existingOrder.getPayrollOrderId());
                    updatedOrder.setPaymentOrderId(existingOrder.getPaymentOrderId());
                    return repository.save(updatedOrder);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> deletePayrollOrder(UUID paymentOrderId, UUID payrollOrderId) {
        return repository.findById(payrollOrderId)
                .filter(order -> order.getPaymentOrderId().equals(paymentOrderId))
                .flatMap(repository::delete);
    }
}