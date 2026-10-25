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

package io.fireflysoftware.core.banking.payments.web.controllers.managed;

import io.fireflysoftware.core.banking.payments.core.managed.*;
import io.fireflysoftware.core.banking.payments.core.managed.impl.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenant}/internal-payments")
@io.swagger.v3.oas.annotations.tags.Tag(name = "ManagedPayments")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "managedPaymentBearer")
@io.swagger.v3.oas.annotations.security.SecurityScheme(
        name = "managedPaymentBearer",
        type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
@ApiResponses({
    @ApiResponse(
            responseCode = "400",
            description = "Invalid or unsupported managed payment input"),
    @ApiResponse(responseCode = "401", description = "A trusted current bearer token is required"),
    @ApiResponse(
            responseCode = "403",
            description = "Current action or exact account authority denied"),
    @ApiResponse(
            responseCode = "404",
            description = "No payment is visible to the current authority"),
    @ApiResponse(
            responseCode = "409",
            description = "Immutable intent, version, effect, or lease conflict"),
    @ApiResponse(
            responseCode = "503",
            description = "Required runtime or delegated peer authority unavailable")
})
public class ManagedPaymentsController {
    private final ManagedPaymentService service;
    private final ManagedPaymentCoordinatorService coordinator;
    private final ManagedPaymentObservationService observations;

    public ManagedPaymentsController(
            ManagedPaymentService service,
            ManagedPaymentCoordinatorService coordinator,
            ManagedPaymentObservationService observations) {
        this.service = service;
        this.coordinator = coordinator;
        this.observations = observations;
    }

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping
    @Operation(operationId = "createManagedPayment")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized payment receipt",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "201",
                description =
                        "New durable payment instruction recorded; no financial effect implied",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @RequestBody Create input) {
        return service.create(tenant, input)
                .map(a -> ResponseEntity.status(a.replayed() ? 200 : 201).body(a.payment()))
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(jwt)));
    }

    @GetMapping("/{paymentId}")
    @Operation(operationId = "getManagedPayment")
    @ApiResponse(
            responseCode = "200",
            description = "Current authorized receipt",
            content = @Content(schema = @Schema(implementation = View.class)))
    public Mono<View> read(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId) {
        return service.read(tenant, paymentId)
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(jwt)));
    }

    @GetMapping("/{paymentId}/history")
    @Operation(operationId = "managedPaymentHistory")
    @ApiResponse(
            responseCode = "200",
            description = "Bounded authorized payment history",
            content =
                    @Content(
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = HistoryEvent.class))))
    public Flux<HistoryEvent> history(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId,
            @RequestParam(defaultValue = "-1") long afterVersion,
            @RequestParam(defaultValue = "25") int limit) {
        return service.history(tenant, paymentId, afterVersion, limit)
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(jwt)));
    }

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping("/{paymentId}/claims")
    @Operation(operationId = "claimManagedPaymentEffect")
    @ApiResponse(
            responseCode = "200",
            description = "Current authorized receipt",
            content = @Content(schema = @Schema(implementation = EffectClaim.class)))
    public Mono<EffectClaim> claim(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId,
            @RequestBody Claim input) {
        return coordinator
                .claim(tenant, paymentId, input)
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(jwt)));
    }

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping("/{paymentId}/failures")
    @Operation(operationId = "recordManagedPaymentFailure")
    @ApiResponse(
            responseCode = "200",
            description = "Current authorized receipt",
            content = @Content(schema = @Schema(implementation = View.class)))
    public Mono<View> fail(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId,
            @RequestBody Failure input) {
        return coordinator
                .recordFailure(tenant, paymentId, input)
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(jwt)));
    }

    @GetMapping("/recovery")
    @Operation(operationId = "managedPaymentRecoveryQueue")
    @ApiResponse(
            responseCode = "200",
            description = "Current authorized receipt",
            content = @Content(schema = @Schema(implementation = RecoveryPage.class)))
    public Mono<RecoveryPage> queue(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @RequestParam(required = false) UUID afterId,
            @RequestParam(defaultValue = "25") int limit) {
        return coordinator
                .recoveryQueue(tenant, afterId, limit)
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(jwt)));
    }

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping("/{paymentId}/acknowledgments")
    @Operation(operationId = "acknowledgeManagedPaymentEffect")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized payment receipt",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "202",
                description = "The exact financial outcome still requires recovery",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> acknowledge(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId,
            @RequestBody Acknowledge input) {
        return observations
                .acknowledge(tenant, paymentId, input)
                .map(
                        v ->
                                ResponseEntity.status(
                                                v.state() == State.RECOVERY_REQUIRED ? 202 : 200)
                                        .body(v))
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(jwt)));
    }

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping("/{paymentId}/decisions")
    @Operation(operationId = "decideManagedPayment")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized payment receipt",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "202",
                description = "Decision recorded with a durable pending participant effect",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> decide(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId,
            @RequestBody Decision input) {
        return coordinator
                .decide(tenant, paymentId, input)
                .map(v -> ResponseEntity.status(v.pendingEffectId() == null ? 200 : 202).body(v))
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(jwt)));
    }

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping("/{paymentId}/recovery")
    @Operation(operationId = "recoverManagedPayment")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized payment receipt",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "202",
                description = "Recovery scheduled with a durable pending participant effect",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> recover(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId,
            @RequestBody Recovery input) {
        return coordinator
                .recover(tenant, paymentId, input)
                .map(v -> ResponseEntity.status(v.pendingEffectId() == null ? 200 : 202).body(v))
                .contextWrite(c -> ManagedPaymentPolicy.with(c, call(jwt)));
    }

    static ManagedPaymentPolicy.Call call(Jwt jwt) {
        if (jwt == null) throw ManagedPaymentPolicy.fail(401, "TRUSTED_PAYMENT_CONTEXT_REQUIRED");
        String scopes = jwt.getClaimAsString("scope");
        var principal =
                SecurityPrincipal.builder()
                        .subject(jwt.getSubject())
                        .issuer(jwt.getIssuer().toString())
                        .tenantId(jwt.getClaimAsString("tenant_id"))
                        .scopes(
                                scopes == null
                                        ? Set.of()
                                        : new HashSet<>(Arrays.asList(scopes.split(" "))))
                        .claims(jwt.getClaims())
                        .build();
        return new ManagedPaymentPolicy.Call(
                principal, jwt.getTokenValue(), Set.copyOf(jwt.getAudience()));
    }
}
