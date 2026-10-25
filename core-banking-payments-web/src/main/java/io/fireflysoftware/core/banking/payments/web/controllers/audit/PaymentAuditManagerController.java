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


package io.fireflysoftware.core.banking.payments.web.controllers.audit;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.services.audit.PaymentAuditManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.audit.PaymentAuditDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;
@Tag(name = "Payment Audit", description = "APIs for accessing payment audit records")
@RestController
public class PaymentAuditManagerController {

    @Autowired
    private PaymentAuditManagerService service;

    @Operation(
            summary = "Retrieve Audit Records by Payment Order ID",
            description = "Retrieves a paginated list of audit records for a specific payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved audit records",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No audit records found", content = @Content)
    })
    @GetMapping(value = "/api/v1/payment-orders/{paymentOrderId}/audit", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentAuditDTO>>> getAuditsByPaymentOrderId(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @ParameterObject
            @ModelAttribute FilterRequest<PaymentAuditDTO> filterRequest
    ) {
        return service.getAuditsByPaymentOrderId(paymentOrderId, filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Retrieve Audit Records by Payment Instruction ID",
            description = "Retrieves a paginated list of audit records for a specific payment instruction."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved audit records",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No audit records found", content = @Content)
    })
    @GetMapping(value = "/api/v1/payment-orders/{paymentOrderId}/instructions/{paymentInstructionId}/audit", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentAuditDTO>>> getAuditsByPaymentInstructionId(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @Parameter(description = "Payment Instruction ID", required = true)
            @PathVariable UUID paymentInstructionId,
            @ParameterObject
            @ModelAttribute FilterRequest<PaymentAuditDTO> filterRequest
    ) {
        return service.getAuditsByPaymentInstructionId(paymentInstructionId, filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Get Audit Record by ID",
            description = "Retrieves a specific audit record by its ID."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved audit record",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentAuditDTO.class))),
            @ApiResponse(responseCode = "404", description = "Audit record not found", content = @Content)
    })
    @GetMapping(value = "/api/v1/audit/{paymentAuditId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentAuditDTO>> getAuditById(
            @Parameter(description = "Payment Audit ID", required = true)
            @PathVariable UUID paymentAuditId
    ) {
        return service.getAuditById(paymentAuditId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
