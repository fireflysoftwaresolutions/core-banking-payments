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


package io.fireflysoftware.core.banking.payments.core.services.debit.impl;

import io.fireflysoftware.core.banking.payments.core.services.debit.*;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.debit.DirectDebitMandateDTO;
import io.fireflysoftware.core.banking.payments.interfaces.enums.debit.DirectDebitMandateStatus;
import io.fireflysoftware.core.banking.payments.models.entities.debit.DirectDebitMandate;
import io.fireflysoftware.core.banking.payments.models.repositories.debit.DirectDebitMandateRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service("directDebitMandateService")
public class DirectDebitMandateServiceImpl implements DirectDebitMandateService {

    private final DirectDebitMandateRepository repository;

    public DirectDebitMandateServiceImpl(DirectDebitMandateRepository repository) {
        this.repository = repository;
    }

    public Mono<DirectDebitMandateDTO> create(DirectDebitMandateDTO request) {
        if (request == null || request.debtorAccountId() == null || request.creditorPartyId() == null) {
            return Mono.error(new IllegalArgumentException("Debtor account and creditor party are required"));
        }
        if (request.currency() == null || !request.currency().matches("[A-Z]{3}")) {
            return Mono.error(new IllegalArgumentException("Currency must be a 3-letter code"));
        }
        if (request.amountLimit() == null || request.amountLimit().signum() < 0) {
            return Mono.error(new IllegalArgumentException("Mandate limit must be zero or positive"));
        }
        if (request.reference() == null || request.reference().isBlank()) {
            return Mono.error(new IllegalArgumentException("Mandate reference is required"));
        }
        DirectDebitMandate row = new DirectDebitMandate();
        row.setDirectDebitMandateId(UUID.randomUUID());
        row.setDebtorAccountId(request.debtorAccountId());
        row.setCreditorPartyId(request.creditorPartyId());
        row.setStatus(request.status() == null ? DirectDebitMandateStatus.ACTIVE : request.status());
        row.setAmountLimit(request.amountLimit());
        row.setCurrency(request.currency());
        row.setReference(request.reference());
        return repository.save(row).map(this::toDto);
    }

    public Mono<DirectDebitMandateDTO> get(UUID mandateId) {
        return repository.findById(mandateId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Direct debit mandate was not found")))
                .map(this::toDto);
    }

    private DirectDebitMandateDTO toDto(DirectDebitMandate row) {
        return new DirectDebitMandateDTO(row.getDirectDebitMandateId(), row.getDebtorAccountId(),
                row.getCreditorPartyId(), row.getStatus(), row.getAmountLimit(), row.getCurrency(), row.getReference());
    }
}
