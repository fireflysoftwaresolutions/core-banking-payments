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


package io.fireflysoftware.core.banking.payments.web.controllers.compliance;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.services.compliance.PaymentComplianceManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.compliance.PaymentComplianceDTO;
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
@Tag(name = "Payment Compliance", description = "APIs for managing payment compliance information")
@RestController
@RequestMapping("/api/v1/payment-orders/{paymentOrderId}/compliance")
public class PaymentComplianceManagerController {

    @Autowired
    private PaymentComplianceManagerService service;

    @Operation(
            summary = "Retrieve Compliance Information",
            description = "Retrieves compliance information for a specific payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved compliance information",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No compliance information found", content = @Content)
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentComplianceDTO>>> getComplianceByPaymentOrderId(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @ParameterObject
            @ModelAttribute FilterRequest<PaymentComplianceDTO> filterRequest
    ) {
        return service.getComplianceByPaymentOrderId(paymentOrderId, filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create Compliance Information",
            description = "Creates compliance information for a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Successfully created compliance information",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentComplianceDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentComplianceDTO>> createCompliance(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @RequestBody PaymentComplianceDTO paymentComplianceDTO
    ) {
        return service.createCompliance(paymentOrderId, paymentComplianceDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @Operation(
            summary = "Update Compliance Information",
            description = "Updates compliance information for a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully updated compliance information",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentComplianceDTO.class))),
            @ApiResponse(responseCode = "404", description = "Compliance information not found", content = @Content)
    })
    @PutMapping(value = "/{paymentComplianceId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentComplianceDTO>> updateCompliance(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @Parameter(description = "Payment Compliance ID", required = true)
            @PathVariable UUID paymentComplianceId,
            @RequestBody PaymentComplianceDTO paymentComplianceDTO
    ) {
        return service.updateCompliance(paymentOrderId, paymentComplianceId, paymentComplianceDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Approve Compliance",
            description = "Approves compliance for a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully approved compliance",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentComplianceDTO.class))),
            @ApiResponse(responseCode = "404", description = "Compliance information not found", content = @Content)
    })
    @PutMapping(value = "/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentComplianceDTO>> approveCompliance(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @RequestParam String approvedBy,
            @RequestParam(required = false) String complianceNotes
    ) {
        return service.approveCompliance(paymentOrderId, approvedBy, complianceNotes)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Reject Compliance",
            description = "Rejects compliance for a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully rejected compliance",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentComplianceDTO.class))),
            @ApiResponse(responseCode = "404", description = "Compliance information not found", content = @Content)
    })
    @PutMapping(value = "/reject", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentComplianceDTO>> rejectCompliance(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @RequestParam String rejectionReason
    ) {
        return service.rejectCompliance(paymentOrderId, rejectionReason)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Delete Compliance Information",
            description = "Deletes compliance information for a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Successfully deleted compliance information"),
            @ApiResponse(responseCode = "404", description = "Compliance information not found", content = @Content)
    })
    @DeleteMapping(value = "/{paymentComplianceId}")
    public Mono<ResponseEntity<Void>> deleteCompliance(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @Parameter(description = "Payment Compliance ID", required = true)
            @PathVariable UUID paymentComplianceId
    ) {
        return service.deleteCompliance(paymentComplianceId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
