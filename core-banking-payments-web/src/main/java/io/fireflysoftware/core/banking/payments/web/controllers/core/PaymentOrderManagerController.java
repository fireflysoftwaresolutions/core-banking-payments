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
import io.fireflysoftware.core.banking.payments.core.services.core.PaymentOrderManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentOrderDTO;
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
@Tag(name = "Payment Orders", description = "APIs for managing payment orders")
@RestController
@RequestMapping("/api/v1/payment-orders")
public class PaymentOrderManagerController {

    @Autowired
    private PaymentOrderManagerService service;
    @Operation(
            summary = "Retrieve Payment Orders",
            description = "Retrieves a paginated list of payment orders based on filtering criteria."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved payment orders",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No payment orders found", content = @Content)
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentOrderDTO>>> getAllPaymentOrders(
            @ParameterObject
            @ModelAttribute FilterRequest<PaymentOrderDTO> filterRequest
    ) {
        return service.getAllPaymentOrders(filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create Payment Order",
            description = "Create a new payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Payment order created successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentOrderDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid payment order data provided", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentOrderDTO>> createPaymentOrder(
            @Parameter(description = "Payment order data to be created", required = true,
                    schema = @Schema(implementation = PaymentOrderDTO.class))
            @RequestBody(required = false) PaymentOrderDTO dto
    ) {
        return service.createPaymentOrder(dto)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    @Operation(
            summary = "Get Payment Order by ID",
            description = "Retrieve a specific payment order by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved the payment order",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentOrderDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment order not found", content = @Content)
    })
    @GetMapping(value = "/{paymentOrderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentOrderDTO>> getPaymentOrderById(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId
    ) {
        return service.getPaymentOrderById(paymentOrderId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Update Payment Order",
            description = "Update an existing payment order by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Payment order updated successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentOrderDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment order not found", content = @Content)
    })
    @PutMapping(value = "/{paymentOrderId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentOrderDTO>> updatePaymentOrder(
            @Parameter(description = "Unique identifier of the payment order to update", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Updated payment order data", required = true,
                    schema = @Schema(implementation = PaymentOrderDTO.class))
            @RequestBody(required = false) PaymentOrderDTO dto
    ) {
        return service.updatePaymentOrder(paymentOrderId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Delete Payment Order",
            description = "Remove an existing payment order by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Payment order deleted successfully", content = @Content),
            @ApiResponse(responseCode = "404", description = "Payment order not found", content = @Content)
    })
    @DeleteMapping("/{paymentOrderId}")
    public Mono<ResponseEntity<Void>> deletePaymentOrder(
            @Parameter(description = "Unique identifier of the payment order to delete", required = true)
            @PathVariable UUID paymentOrderId
    ) {
        return service.deletePaymentOrder(paymentOrderId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}