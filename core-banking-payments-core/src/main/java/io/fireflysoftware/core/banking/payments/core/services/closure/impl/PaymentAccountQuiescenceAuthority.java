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

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

@Component
public final class PaymentAccountQuiescenceAuthority {
    private static final Set<String> OPERATIONS = Set.of("quiesce", "read", "observe", "release");
    private final ObjectProvider<SecurityContextPort> contexts;
    private final ObjectProvider<PolicyDecisionPort> policies;
    private final Environment environment;

    public PaymentAccountQuiescenceAuthority(
            ObjectProvider<SecurityContextPort> contexts,
            ObjectProvider<PolicyDecisionPort> policies,
            Environment environment) {
        this.contexts = contexts;
        this.policies = policies;
        this.environment = environment;
    }

    public record Admission(
            SecurityPrincipal actor,
            String tenantId,
            UUID accountId,
            String operation,
            Map<String, Object> exactContext,
            long validUntilEpochMillis) {
        public Admission {
            exactContext = Map.copyOf(exactContext);
        }

        public void requireCurrent() {
            current(actor);
            if (validUntilEpochMillis <= Instant.now().toEpochMilli()) throw denied();
        }
    }

    public Mono<Admission> authorize(
            String tenant, UUID account, String operation, Map<String, Object> exact) {
        return PaymentAccountQuiescenceBoundary.outsideTransaction(
                () ->
                        Mono.defer(
                                        () -> {
                                            if (!Boolean.TRUE.equals(
                                                            environment.getProperty(
                                                                    "firefly.security.resource-server.strict-policy",
                                                                    Boolean.class))
                                                    || !OPERATIONS.contains(operation)
                                                    || tenant == null
                                                    || !tenant.matches("[A-Za-z0-9_-]{1,64}")
                                                    || account == null
                                                    || account.equals(new UUID(0, 0))
                                                    || exact == null
                                                    || exact.size() > 20
                                                    || exact.containsKey("tenantId")
                                                    || exact.containsKey("accountId"))
                                                return Mono.error(denied());
                                            var context = contexts.getIfAvailable();
                                            var policy = policies.getIfAvailable();
                                            if (context == null || policy == null)
                                                return Mono.error(denied());
                                            var values = new HashMap<String, Object>(exact);
                                            values.put("tenantId", tenant);
                                            values.put("accountId", account.toString());
                                            return context.currentPrincipal()
                                                    .flatMap(
                                                            actor -> {
                                                                current(actor);
                                                                if (!tenant.equals(actor.tenantId())
                                                                        || !actor.hasScope(
                                                                                "payments:closure:"
                                                                                        + operation)
                                                                        || !(actor.claims()
                                                                                        .get(
                                                                                                "funds_accounts")
                                                                                instanceof
                                                                                List<?> resources)
                                                                        || resources.isEmpty()
                                                                        || resources.size() > 1000
                                                                        || !resources.contains(
                                                                                account.toString()))
                                                                    return Mono.error(denied());
                                                                return policy.authorize(
                                                                                actor,
                                                                                "payments.account-closure."
                                                                                        + operation,
                                                                                "payments/account/"
                                                                                        + account,
                                                                                Map.copyOf(values))
                                                                        .filter(
                                                                                d ->
                                                                                        d.granted()
                                                                                                && d
                                                                                                                .obligations()
                                                                                                        != null
                                                                                                && d.obligations()
                                                                                                        .isEmpty())
                                                                        .map(
                                                                                d -> {
                                                                                    current(actor);
                                                                                    return new Admission(
                                                                                            actor,
                                                                                            tenant,
                                                                                            account,
                                                                                            operation,
                                                                                            Map
                                                                                                    .copyOf(
                                                                                                            exact),
                                                                                            Math
                                                                                                    .min(
                                                                                                            ((Instant)
                                                                                                                            actor.claims()
                                                                                                                                    .get(
                                                                                                                                            "exp"))
                                                                                                                    .toEpochMilli(),
                                                                                                            Instant
                                                                                                                    .now()
                                                                                                                    .plusSeconds(
                                                                                                                            5)
                                                                                                                    .toEpochMilli()));
                                                                                });
                                                            })
                                                    .switchIfEmpty(Mono.error(denied()));
                                        })
                                .timeout(Duration.ofSeconds(5))
                                .onErrorMap(e -> denied()));
    }

    private static void current(SecurityPrincipal actor) {
        if (actor == null
                || actor.subject() == null
                || actor.subject().isBlank()
                || actor.subject().length() > 255
                || actor.issuer() == null
                || !actor.issuer().matches("https?://[^ /]+.*")
                || !(actor.claims().get("exp") instanceof Instant expiry)
                || !expiry.isAfter(Instant.now())) throw denied();
    }

    static ResponseStatusException denied() {
        return new ResponseStatusException(
                HttpStatus.FORBIDDEN, "CURRENT_EXACT_PAYMENT_CLOSURE_AUTHORITY_REQUIRED");
    }
}
