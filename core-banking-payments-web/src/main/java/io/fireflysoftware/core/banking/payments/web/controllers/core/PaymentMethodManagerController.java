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
import io.fireflysoftware.core.banking.payments.core.services.core.PaymentMethodManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentMethodDTO;
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
@Tag(name = "Payment Methods", description = "APIs for managing payment methods")
@RestController
@RequestMapping("/api/v1/payment-methods")
public class PaymentMethodManagerController {

    @Autowired
    private PaymentMethodManagerService service;

    @Operation(
            summary = "Retrieve Payment Methods",
            description = "Retrieves a paginated list of payment methods based on filtering criteria."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved payment methods",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No payment methods found", content = @Content)
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentMethodDTO>>> getAllPaymentMethods(
            @ParameterObject
            @ModelAttribute FilterRequest<PaymentMethodDTO> filterRequest
    ) {
        return service.getAllPaymentMethods(filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create Payment Method",
            description = "Creates a new payment method."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Payment method created successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentMethodDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid payment method data provided", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentMethodDTO>> createPaymentMethod(
            @Parameter(description = "Payment method data to be created", required = true,
                    schema = @Schema(implementation = PaymentMethodDTO.class))
            @RequestBody(required = false) PaymentMethodDTO dto
    ) {
        return service.createPaymentMethod(dto)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    @Operation(
            summary = "Get Payment Method by ID",
            description = "Retrieve a specific payment method by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved the payment method",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentMethodDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment method not found", content = @Content)
    })
    @GetMapping(value = "/{paymentMethodId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentMethodDTO>> getPaymentMethodById(
            @Parameter(description = "Unique identifier of the payment method", required = true)
            @PathVariable UUID paymentMethodId
    ) {
        return service.getPaymentMethodById(paymentMethodId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Update Payment Method",
            description = "Update an existing payment method by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Payment method updated successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentMethodDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment method not found", content = @Content)
    })
    @PutMapping(value = "/{paymentMethodId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentMethodDTO>> updatePaymentMethod(
            @Parameter(description = "Unique identifier of the payment method to update", required = true)
            @PathVariable UUID paymentMethodId,

            @Parameter(description = "Updated payment method data", required = true,
                    schema = @Schema(implementation = PaymentMethodDTO.class))
            @RequestBody(required = false) PaymentMethodDTO dto
    ) {
        return service.updatePaymentMethod(paymentMethodId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Delete Payment Method",
            description = "Remove an existing payment method by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Payment method deleted successfully", content = @Content),
            @ApiResponse(responseCode = "404", description = "Payment method not found", content = @Content)
    })
    @DeleteMapping("/{paymentMethodId}")
    public Mono<ResponseEntity<Void>> deletePaymentMethod(
            @Parameter(description = "Unique identifier of the payment method to delete", required = true)
            @PathVariable UUID paymentMethodId
    ) {
        return service.deletePaymentMethod(paymentMethodId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}