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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.*;

class PaymentAccountQuiescenceAuthorityTest {
    final UUID account = UUID.fromString("10000000-0000-0000-0000-000000000001");
    final MockEnvironment environment =
            new MockEnvironment()
                    .withProperty("firefly.security.resource-server.strict-policy", "true");
    final ObjectProvider<SecurityContextPort> contexts = mock(ObjectProvider.class);
    final ObjectProvider<PolicyDecisionPort> policies = mock(ObjectProvider.class);

    SecurityPrincipal principal() {
        return SecurityPrincipal.builder()
                .subject("operator")
                .issuer("https://issuer.invalid")
                .tenantId("bank")
                .scopes(Set.of("payments:closure:quiesce"))
                .claims(
                        Map.of(
                                "exp",
                                Instant.now().plusSeconds(60),
                                "funds_accounts",
                                List.of(account.toString())))
                .build();
    }

    PaymentAccountQuiescenceAuthority authority(PolicyDecisionPort policy) {
        when(contexts.getIfAvailable()).thenReturn(() -> Mono.just(principal()));
        when(policies.getIfAvailable()).thenReturn(policy);
        return new PaymentAccountQuiescenceAuthority(contexts, policies, environment);
    }

    Mono<PaymentAccountQuiescenceAuthority.Admission> call(PolicyDecisionPort policy) {
        return authority(policy)
                .authorize(
                        "bank",
                        account,
                        "quiesce",
                        Map.of("closureId", "20000000-0000-0000-0000-000000000001"));
    }

    @Test
    void missingPolicyCannotAuthorize() {
        denied(call(null));
    }

    @Test
    void emptyDecisionCannotAuthorize() {
        denied(call((p, a, r, c) -> Mono.empty()));
    }

    @Test
    void deniedDecisionCannotAuthorize() {
        denied(call((p, a, r, c) -> Mono.just(Decision.deny("withdrawn"))));
    }

    @Test
    void disabledStrictPolicyCannotAuthorize() {
        environment.withProperty("firefly.security.resource-server.strict-policy", "false");
        denied(call((p, a, r, c) -> Mono.just(Decision.permit())));
    }

    @Test
    void policyFailureCannotAuthorize() {
        denied(call((p, a, r, c) -> Mono.error(new IllegalStateException("offline"))));
    }

    @Test
    void wrongTenantCannotAuthorize() {
        var a = authority((p, action, r, c) -> Mono.just(Decision.permit()));
        denied(a.authorize("other", account, "quiesce", Map.of()));
    }

    @Test
    void unknownOperationCannotAuthorize() {
        var a = authority((p, action, r, c) -> Mono.just(Decision.permit()));
        denied(a.authorize("bank", account, "CLEAR", Map.of()));
    }

    @Test
    void currentExactAccountPolicyProducesFiniteAdmission() {
        StepVerifier.create(
                        call(
                                (p, action, resource, context) -> {
                                    assertThat(action)
                                            .isEqualTo("payments.account-closure.quiesce");
                                    assertThat(resource)
                                            .isEqualTo(
                                                    "payments/account/10000000-0000-0000-0000-000000000001");
                                    assertThat(context)
                                            .containsEntry("tenantId", "bank")
                                            .containsEntry("accountId", account.toString());
                                    return Mono.just(Decision.permit());
                                }))
                .assertNext(
                        a -> {
                            assertThat(a.actor().subject()).isEqualTo("operator");
                            assertThat(a.validUntilEpochMillis())
                                    .isBetween(
                                            Instant.now().toEpochMilli(),
                                            Instant.now().plusSeconds(6).toEpochMilli());
                            a.requireCurrent();
                        })
                .verifyComplete();
    }

    void denied(Mono<?> operation) {
        StepVerifier.create(operation)
                .expectErrorSatisfies(
                        e -> {
                            assertThat(e).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) e).getStatusCode().value())
                                    .isEqualTo(403);
                        })
                .verify();
    }
}
