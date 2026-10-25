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
import io.fireflysoftware.core.banking.payments.core.services.core.PaymentFeeManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentFeeDTO;
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
@Tag(name = "Payment Fees",
        description = "APIs for managing payment fees")
@RestController
@RequestMapping("/api/v1/payment-orders/{paymentOrderId}/fees")
public class PaymentFeeManagerController {

    @Autowired
    private PaymentFeeManagerService service;

    @ModelAttribute("filterRequest")
    public FilterRequest<PaymentFeeDTO> paymentFeeFilterRequest() {
        FilterRequest<PaymentFeeDTO> request = new FilterRequest<>();
        request.setFilters(new PaymentFeeDTO());
        return request;
    }

    @Operation(
            summary = "Retrieve Payment Fees",
            description = "Retrieves a paginated list of payment fees for a specific payment order, with optional filtering."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved payment fees",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No payment fees found", content = @Content)
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentFeeDTO>>> getAllPaymentFees(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @ParameterObject
            @ModelAttribute("filterRequest") FilterRequest<PaymentFeeDTO> filterRequest
    ) {
        return service.getAllPaymentFees(paymentOrderId, filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create Payment Fee",
            description = "Creates a new payment fee for the specified payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Payment fee created successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentFeeDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid payment fee data provided", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentFeeDTO>> createPaymentFee(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Payment fee data to be created", required = true,
                    schema = @Schema(implementation = PaymentFeeDTO.class))
            @RequestBody(required = false) PaymentFeeDTO dto
    ) {
        return service.createPaymentFee(paymentOrderId, dto)
                .map(createdFee -> ResponseEntity.status(HttpStatus.CREATED).body(createdFee))
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    @Operation(
            summary = "Get Payment Fee by ID",
            description = "Retrieve a specific payment fee by its unique identifier within the context of a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved the payment fee",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentFeeDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment fee not found", content = @Content)
    })
    @GetMapping(value = "/{paymentFeeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentFeeDTO>> getPaymentFeeById(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Unique identifier of the payment fee", required = true)
            @PathVariable UUID paymentFeeId
    ) {
        return service.getPaymentFeeById(paymentOrderId, paymentFeeId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Update Payment Fee",
            description = "Update an existing payment fee by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Payment fee updated successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentFeeDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment fee not found", content = @Content)
    })
    @PutMapping(value = "/{paymentFeeId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentFeeDTO>> updatePaymentFee(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Unique identifier of the payment fee to update", required = true)
            @PathVariable UUID paymentFeeId,

            @Parameter(description = "Updated payment fee data", required = true,
                    schema = @Schema(implementation = PaymentFeeDTO.class))
            @RequestBody(required = false) PaymentFeeDTO dto
    ) {
        return service.updatePaymentFee(paymentOrderId, paymentFeeId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Delete Payment Fee",
            description = "Remove an existing payment fee by its unique identifier, within the context of a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Payment fee deleted successfully", content = @Content),
            @ApiResponse(responseCode = "404", description = "Payment fee not found", content = @Content)
    })
    @DeleteMapping("/{paymentFeeId}")
    public Mono<ResponseEntity<Void>> deletePaymentFee(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Unique identifier of the payment fee to delete", required = true)
            @PathVariable UUID paymentFeeId
    ) {
        return service.deletePaymentFee(paymentOrderId, paymentFeeId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}