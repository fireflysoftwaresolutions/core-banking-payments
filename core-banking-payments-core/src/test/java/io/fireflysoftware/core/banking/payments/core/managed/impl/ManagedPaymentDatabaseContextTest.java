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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.r2dbc.spi.ConnectionFactory;

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionContextManager;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.*;

class ManagedPaymentDatabaseContextTest {
    private final UUID account = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final ConnectionFactory owned = mock(ConnectionFactory.class);
    private final DatabaseClient database = mock(DatabaseClient.class);
    private final org.springframework.data.r2dbc.core.R2dbcEntityOperations operations = mock(org.springframework.data.r2dbc.core.R2dbcEntityOperations.class);
    private final io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentNativeRepository nativeRepository = mock(io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentNativeRepository.class);
    private ManagedPaymentDatabaseContext context;

    private Mono<Void> establish() {
        when(database.getConnectionFactory()).thenReturn(owned);
        when(operations.getDatabaseClient()).thenReturn(database);
        context = new ManagedPaymentDatabaseContext(operations, nativeRepository);
        when(nativeRepository.runtimeAllowed()).thenThrow(new AssertionError("Repository I/O before native transaction proof"));
        when(database.sql(anyString()))
                .thenThrow(new AssertionError("SQL before native transaction proof"));
        var principal =
                SecurityPrincipal.builder()
                        .subject("closure-worker")
                        .issuer("https://issuer.invalid")
                        .tenantId("bank")
                        .scopes(Set.of("payments:create"))
                        .claims(
                                Map.of(
                                        "exp",
                                        Instant.now().plusSeconds(60),
                                        "funds_accounts",
                                        List.of(account.toString())))
                        .build();
        return Mono.defer(
                () ->
                        context.establish(
                                new ManagedPaymentPolicy.Call(principal, "unused", Set.of()),
                                "bank",
                                "CREATE",
                                account));
    }

    @Test
    void activeTransactionForAnotherFactoryCannotEstablishNativeContext() {
        var attempt =
                TransactionSynchronizationManager.forCurrentTransaction()
                        .flatMap(
                                manager -> {
                                    manager.setActualTransactionActive(true);
                                    manager.bindResource(
                                            mock(ConnectionFactory.class), new Object());
                                    return establish();
                                })
                        .contextWrite(TransactionContextManager.createTransactionContext());
        denied(attempt);
    }

    @Test
    void absenceOfTransactionCannotEstablishNativeContext() {
        denied(establish());
    }

    @Test
    void boundFactoryWithoutActiveTransactionCannotEstablishNativeContext() {
        var attempt =
                TransactionSynchronizationManager.forCurrentTransaction()
                        .flatMap(
                                manager -> {
                                    manager.bindResource(owned, new Object());
                                    return establish();
                                })
                        .contextWrite(TransactionContextManager.createTransactionContext());
        denied(attempt);
    }

    private void denied(Mono<Void> attempt) {
        StepVerifier.create(attempt)
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) error).getStatusCode().value())
                                    .isEqualTo(503);
                        })
                .verify();
        verifyNoInteractions(nativeRepository);
    }
}
