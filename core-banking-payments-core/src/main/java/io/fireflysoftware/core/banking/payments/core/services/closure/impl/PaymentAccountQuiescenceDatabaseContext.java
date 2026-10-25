/*
 * Copyright 2026 Firefly Software Foundation
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

package io.fireflysoftware.core.banking.payments.core.services.closure.impl;

import io.fireflysoftware.core.banking.payments.core.services.closure.*;

import io.fireflysoftware.core.banking.payments.models.repositories.closure.PaymentAccountNativeRepository;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

@Component
public final class PaymentAccountQuiescenceDatabaseContext {
    private final ConnectionFactory factory;
    private final PaymentAccountNativeRepository nativeRepository;

    public PaymentAccountQuiescenceDatabaseContext(R2dbcEntityOperations operations, PaymentAccountNativeRepository nativeRepository) {
        this.factory = operations.getDatabaseClient().getConnectionFactory();
        this.nativeRepository = nativeRepository;
    }

    Mono<Void> establish(PaymentAccountQuiescenceAuthority.Admission admission) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .onErrorMap(NoTransactionException.class, error -> PaymentAccountQuiescenceStore.unavailable())
                .flatMap(manager -> {
                    if (!manager.isActualTransactionActive() || !manager.hasResource(factory))
                        return Mono.error(PaymentAccountQuiescenceStore.unavailable());
                    admission.requireCurrent();
                    return nativeRepository.runtimeAllowed(admission.validUntilEpochMillis())
                            .filter(Boolean.TRUE::equals)
                            .switchIfEmpty(Mono.error(PaymentAccountQuiescenceStore.unavailable()))
                            .then(Mono.defer(() -> {
                                admission.requireCurrent();
                                return nativeRepository.establishContext(admission.tenantId(), admission.accountId().toString(),
                                        admission.operation(), Long.toString(admission.validUntilEpochMillis()),
                                        admission.actor().issuer(), admission.actor().subject());
                            }));
                });
    }
}
