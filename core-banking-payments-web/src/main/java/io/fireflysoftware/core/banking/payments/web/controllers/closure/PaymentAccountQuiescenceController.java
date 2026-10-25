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

package io.fireflysoftware.core.banking.payments.web.controllers.closure;

import io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicy;
import io.fireflysoftware.core.banking.payments.core.services.closure.*;
import io.fireflysoftware.core.banking.payments.core.services.closure.impl.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.closure.PaymentAccountQuiescenceContracts.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenant}/internal-payments/accounts/{accountId}/quiescence")
@io.swagger.v3.oas.annotations.tags.Tag(name = "PaymentAccountQuiescence")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "managedPaymentBearer")
public final class PaymentAccountQuiescenceController {
    private final PaymentAccountQuiescenceService service;

    public PaymentAccountQuiescenceController(PaymentAccountQuiescenceService service) {
        this.service = service;
    }

    @DisableIdempotency
    @PostMapping
    @Operation(
            operationId = "quiesceManagedPaymentsForAccount",
            summary = "Retain an internal-payment intake fence; no business account closure",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content = @Content(schema = @Schema(implementation = Quiesce.class))))
    public Mono<QuiescenceReceipt> quiesce(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    SecurityPrincipal actor,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("Authorization")
                    String authorization,
            @PathVariable String tenant,
            @PathVariable UUID accountId,
            @RequestHeader("X-Idempotency-Key") UUID actionId,
            @RequestBody byte[] raw) {
        if (!actionId.equals(PaymentAccountQuiescenceRules.decode(raw).actionId()))
            throw ManagedPaymentPolicy.fail(409, "PAYMENT_CLOSURE_ACTION_HEADER_CONFLICT");
        return service.quiesce(tenant, accountId, raw)
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(actor, authorization)));
    }

    @DisableIdempotency
    @GetMapping("/{closureId}")
    @Operation(
            operationId = "getManagedPaymentAccountQuiescence",
            summary = "Read current authorized retained fence progress")
    public Mono<View> get(
            @PathVariable String tenant,
            @PathVariable UUID accountId,
            @PathVariable UUID closureId) {
        return service.get(tenant, accountId, closureId);
    }

    @DisableIdempotency
    @GetMapping("/{closureId}/obligations")
    @Operation(
            operationId = "getManagedPaymentAccountObligations",
            summary = "Capture or page a complete retained managed-payment observation")
    public Mono<ObligationPage> obligations(
            @PathVariable String tenant,
            @PathVariable UUID accountId,
            @PathVariable UUID closureId,
            @RequestParam(required = false) UUID observationId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit) {
        return service.obligations(tenant, accountId, closureId, observationId, after, limit);
    }

    @DisableIdempotency
    @PostMapping("/release")
    @Operation(
            operationId = "releaseManagedPaymentAccountQuiescence",
            summary = "Explicitly release a retained fence under current authority and CAS",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content = @Content(schema = @Schema(implementation = Release.class))))
    public Mono<ReleaseReceipt> release(
            @PathVariable String tenant,
            @PathVariable UUID accountId,
            @RequestHeader("X-Idempotency-Key") UUID actionId,
            @RequestBody byte[] raw) {
        if (!actionId.equals(PaymentAccountQuiescenceRules.decodeRelease(raw).actionId()))
            throw ManagedPaymentPolicy.fail(409, "PAYMENT_CLOSURE_ACTION_HEADER_CONFLICT");
        return service.release(tenant, accountId, raw);
    }

    private static ManagedPaymentPolicy.Call call(SecurityPrincipal actor, String authorization) {
        if (actor == null
                || authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || authorization.length() <= 7)
            throw ManagedPaymentPolicy.fail(401, "TRUSTED_PAYMENT_CONTEXT_REQUIRED");
        Object audiences = actor.claims().get("aud");
        if (!(audiences instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String)))
            throw ManagedPaymentPolicy.fail(401, "TRUSTED_PAYMENT_AUDIENCE_REQUIRED");
        return new ManagedPaymentPolicy.Call(
                actor,
                authorization.substring(7),
                values.stream()
                        .map(Object::toString)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
}
