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
import io.fireflysoftware.core.banking.payments.core.services.core.PaymentScheduleManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.core.PaymentScheduleDTO;
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
@Tag(name = "Payment Schedules",
        description = "APIs for managing payment schedules")
@RestController
@RequestMapping("/api/v1/payment-orders/{paymentOrderId}/schedules")
public class PaymentScheduleManagerController {

    @Autowired
    private PaymentScheduleManagerService service;

    @Operation(
            summary = "Retrieve Payment Schedules",
            description = "Retrieves a paginated list of payment schedules for a specific payment order, with optional filtering."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved payment schedules",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No payment schedules found", content = @Content)
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentScheduleDTO>>> getAllPaymentSchedules(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @ParameterObject
            @ModelAttribute FilterRequest<PaymentScheduleDTO> filterRequest
    ) {
        return service.getAllPaymentSchedules(paymentOrderId, filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create Payment Schedule",
            description = "Creates a new payment schedule for the specified payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Payment schedule created successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentScheduleDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid payment schedule data provided", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentScheduleDTO>> createPaymentSchedule(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Payment schedule data to be created", required = true,
                    schema = @Schema(implementation = PaymentScheduleDTO.class))
            @RequestBody(required = false) PaymentScheduleDTO dto
    ) {
        return service.createPaymentSchedule(paymentOrderId, dto)
                .map(createdSchedule -> ResponseEntity.status(HttpStatus.CREATED).body(createdSchedule))
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    @Operation(
            summary = "Get Payment Schedule by ID",
            description = "Retrieve a specific payment schedule by its unique identifier, within the context of a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved the payment schedule",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentScheduleDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment schedule not found", content = @Content)
    })
    @GetMapping(value = "/{paymentScheduleId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentScheduleDTO>> getPaymentScheduleById(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Unique identifier of the payment schedule", required = true)
            @PathVariable UUID paymentScheduleId
    ) {
        return service.getPaymentScheduleById(paymentOrderId, paymentScheduleId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Update Payment Schedule",
            description = "Update an existing payment schedule by its unique identifier."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Payment schedule updated successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentScheduleDTO.class))),
            @ApiResponse(responseCode = "404", description = "Payment schedule not found", content = @Content)
    })
    @PutMapping(value = "/{paymentScheduleId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentScheduleDTO>> updatePaymentSchedule(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Unique identifier of the payment schedule to update", required = true)
            @PathVariable UUID paymentScheduleId,

            @Parameter(description = "Updated payment schedule data", required = true,
                    schema = @Schema(implementation = PaymentScheduleDTO.class))
            @RequestBody(required = false) PaymentScheduleDTO dto
    ) {
        return service.updatePaymentSchedule(paymentOrderId, paymentScheduleId, dto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Delete Payment Schedule",
            description = "Remove an existing payment schedule by its unique identifier, within the context of a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Payment schedule deleted successfully",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Payment schedule not found", content = @Content)
    })
    @DeleteMapping("/{paymentScheduleId}")
    public Mono<ResponseEntity<Void>> deletePaymentSchedule(
            @Parameter(description = "Unique identifier of the payment order", required = true)
            @PathVariable UUID paymentOrderId,

            @Parameter(description = "Unique identifier of the payment schedule to delete", required = true)
            @PathVariable UUID paymentScheduleId
    ) {
        return service.deletePaymentSchedule(paymentOrderId, paymentScheduleId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}