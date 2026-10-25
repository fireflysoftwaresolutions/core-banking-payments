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

package io.fireflysoftware.core.banking.payments.core.managed.impl;

import io.fireflysoftware.core.banking.payments.core.managed.*;

import static io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicy.*;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentNativeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;

import reactor.core.publisher.Mono;

import java.util.*;

@Component
public final class ManagedPaymentDatabaseContext {
    private final ConnectionFactory factory;
    private final ManagedPaymentNativeRepository nativeRepository;

    public ManagedPaymentDatabaseContext(R2dbcEntityOperations operations, ManagedPaymentNativeRepository nativeRepository) {
        this.factory = operations.getDatabaseClient().getConnectionFactory();
        this.nativeRepository = nativeRepository;
    }

    Mono<Void> establish(Call call, String tenant, String purpose, UUID... accounts) {
        return establish(
                call,
                tenant,
                purpose,
                "CREATE".equals(purpose) ? "payments:create" : "payments:read",
                accounts);
    }

    Mono<Void> establish(Call call, String tenant, String purpose, String scope, UUID... accounts) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .onErrorMap(
                        NoTransactionException.class,
                        error -> fail(503, "NATIVE_PAYMENT_TRANSACTION_REQUIRED"))
                .flatMap(
                        manager -> {
                            if (!manager.isActualTransactionActive()
                                    || !manager.hasResource(factory))
                                return Mono.error(fail(503, "NATIVE_PAYMENT_TRANSACTION_REQUIRED"));
                            return Mono.defer(
                                    () -> establishBound(call, tenant, purpose, scope, accounts));
                        });
    }

    private Mono<Void> establishBound(
            Call call, String tenant, String purpose, String scope, UUID... accounts) {
        require(call, tenant, scope, accounts);
        if (!Set.of("CREATE", "READ", "MUTATE", "OBSERVE").contains(purpose))
            return Mono.error(fail(403, "NATIVE_PAYMENT_PURPOSE_REQUIRED"));
        String resources =
                Arrays.stream(accounts)
                        .distinct()
                        .map(a -> "\"" + a + "\"")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return nativeRepository.runtimeAllowed()
                .filter(Boolean.TRUE::equals)
                .switchIfEmpty(Mono.error(fail(503, "NONPRIVILEGED_PAYMENT_RUNTIME_REQUIRED")))
                .then(nativeRepository.establishContext(tenant, resources, purpose,
                        Long.toString(((java.time.Instant) call.principal().claims().get("exp")).toEpochMilli())));
    }
}
