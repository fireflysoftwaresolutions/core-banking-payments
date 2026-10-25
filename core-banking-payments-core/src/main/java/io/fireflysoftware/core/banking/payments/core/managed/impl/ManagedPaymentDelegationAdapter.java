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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.*;

@Component
@ConditionalOnMissingBean(
        value = PaymentDelegationPort.class,
        ignored = ManagedPaymentDelegationAdapter.class)
public class ManagedPaymentDelegationAdapter implements PaymentDelegationPort {
    private final String mode;

    public ManagedPaymentDelegationAdapter(
            @Value("${managed-payments.delegation.mode:unconfigured}") String mode) {
        this.mode = mode;
    }

    public Mono<String> token(
            String audience, String tenant, Set<UUID> accounts, Set<String> requiredScopes) {
        return current()
                .flatMap(
                        call -> {
                            if (!"validated-multi-audience".equals(mode))
                                return Mono.error(fail(503, "TRUSTED_PAYMENT_DELEGATION_REQUIRED"));
                            if (audience == null || audience.isBlank() || !call.audience(audience))
                                return Mono.error(fail(403, "PAYMENT_PEER_AUDIENCE_DENIED"));
                            for (String scope : requiredScopes)
                                require(call, tenant, scope, accounts.toArray(UUID[]::new));
                            return Mono.just(call.bearer());
                        });
    }
}
