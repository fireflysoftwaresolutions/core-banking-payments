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


package io.fireflysoftware.core.banking.payments.web.controllers.core;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.services.core.PaymentProofManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentProofDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Tag(name = "Payment Proofs",
        description = "APIs for managing payment proofs")
@RestController
@RequestMapping("/api/v1/payment-orders/{paymentOrderId}/proof")
public class PaymentProofManagerController {

    @Autowired
    private PaymentProofManagerService service;

    @Operation(
            summary = "Retrieve Payment Proofs",
            description = "Retrieves a paginated list of payment proofs for a specific payment order, with optional filtering."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved payment proofs",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No payment proofs found", content = @Content)
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentProofDTO>>> getAllPaymentProofs(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @ParameterObject
            @ModelAttribute FilterRequest<PaymentProofDTO> filterRequest
    ) {
        return service.getAllPaymentProofs(paymentOrderId, filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create Payment Proof",
            description = "Creates a new payment proof for the specified payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Payment proof created successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentProofDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid payment proof data provided", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentProofDTO>> createPaymentProof(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Payment proof data to be created", required = true,
                    schema = @Schema(implementation = PaymentProofDTO.class))
            @RequestBody(required = false) PaymentProofDTO dto
    ) {
        return service.createPaymentProof(paymentOrderId, dto)
                .map(createdProof -> ResponseEntity.status(HttpStatus.CREATED).body(createdProof))
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    @Operation(
            summary = "Get Payment Proof by ID",
            description = "Retrieve a specific payment proof by its unique identifier, within the context of a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved the payment proof",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentProofDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment proof not found", content = @Content)
    })
    @GetMapping(value = "/{paymentProofId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentProofDTO>> getPaymentProofById(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Unique identifier of the payment proof", required = true)
            @PathVariable UUID paymentProofId
    ) {
        return service.getPaymentProofById(paymentOrderId, paymentProofId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Update Payment Proof",
            description = "Update an existing payment proof by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Payment proof updated successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentProofDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment proof not found", content = @Content)
    })
    @PutMapping(value = "/{paymentProofId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentProofDTO>> updatePaymentProof(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Unique identifier of the payment proof to update", required = true)
            @PathVariable UUID paymentProofId,

            @Parameter(description = "Updated payment proof data", required = true,
                    schema = @Schema(implementation = PaymentProofDTO.class))
            @RequestBody(required = false) PaymentProofDTO dto
    ) {
        return service.updatePaymentProof(paymentOrderId, paymentProofId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Delete Payment Proof",
            description = "Remove an existing payment proof by its unique identifier, within the context of a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Payment proof deleted successfully", content = @Content),
            @ApiResponse(responseCode = "404", description = "Payment proof not found", content = @Content)
    })
    @DeleteMapping("/{paymentProofId}")
    public Mono<ResponseEntity<Void>> deletePaymentProof(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Unique identifier of the payment proof to delete", required = true)
            @PathVariable UUID paymentProofId
    ) {
        return service.deletePaymentProof(paymentOrderId, paymentProofId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}