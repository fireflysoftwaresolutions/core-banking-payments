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


package io.fireflysoftware.core.banking.payments.web.controllers.beneficiary;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.services.beneficiary.PaymentBeneficiaryManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.beneficiary.PaymentBeneficiaryDTO;
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
@Tag(name = "Payment Beneficiaries", description = "APIs for managing payment beneficiaries")
@RestController
@RequestMapping("/api/v1/payment-beneficiaries")
public class PaymentBeneficiaryManagerController {

    @Autowired
    private PaymentBeneficiaryManagerService service;

    @Operation(
            summary = "Retrieve Beneficiaries",
            description = "Retrieves a paginated list of beneficiaries with optional filtering."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved beneficiaries",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No beneficiaries found", content = @Content)
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentBeneficiaryDTO>>> getAllBeneficiaries(
            @Parameter(description = "Payer Account ID")
            @RequestParam(required = false) UUID payerAccountId,
            @Parameter(description = "Is Favorite")
            @RequestParam(required = false) Boolean isFavorite,
            @ParameterObject
            @ModelAttribute FilterRequest<PaymentBeneficiaryDTO> filterRequest
    ) {
        if (payerAccountId != null) {
            if (Boolean.TRUE.equals(isFavorite)) {
                return service.getFavoriteBeneficiariesByPayerAccountId(payerAccountId, filterRequest)
                        .map(ResponseEntity::ok)
                        .defaultIfEmpty(ResponseEntity.notFound().build());
            } else {
                return service.getBeneficiariesByPayerAccountId(payerAccountId, filterRequest)
                        .map(ResponseEntity::ok)
                        .defaultIfEmpty(ResponseEntity.notFound().build());
            }
        } else {
            return service.getAllBeneficiaries(filterRequest)
                    .map(ResponseEntity::ok)
                    .defaultIfEmpty(ResponseEntity.notFound().build());
        }
    }

    @Operation(
            summary = "Create Beneficiary",
            description = "Creates a new beneficiary."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Successfully created beneficiary",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentBeneficiaryDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentBeneficiaryDTO>> createBeneficiary(
            @RequestBody PaymentBeneficiaryDTO paymentBeneficiaryDTO
    ) {
        return service.createBeneficiary(paymentBeneficiaryDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @Operation(
            summary = "Get Beneficiary by ID",
            description = "Retrieves a specific beneficiary by its ID."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved beneficiary",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentBeneficiaryDTO.class))),
            @ApiResponse(responseCode = "404", description = "Beneficiary not found", content = @Content)
    })
    @GetMapping(value = "/{paymentBeneficiaryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentBeneficiaryDTO>> getBeneficiaryById(
            @Parameter(description = "Payment Beneficiary ID", required = true)
            @PathVariable UUID paymentBeneficiaryId
    ) {
        return service.getBeneficiaryById(paymentBeneficiaryId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Update Beneficiary",
            description = "Updates a beneficiary."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully updated beneficiary",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentBeneficiaryDTO.class))),
            @ApiResponse(responseCode = "404", description = "Beneficiary not found", content = @Content)
    })
    @PutMapping(value = "/{paymentBeneficiaryId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentBeneficiaryDTO>> updateBeneficiary(
            @Parameter(description = "Payment Beneficiary ID", required = true)
            @PathVariable UUID paymentBeneficiaryId,
            @RequestBody PaymentBeneficiaryDTO paymentBeneficiaryDTO
    ) {
        return service.updateBeneficiary(paymentBeneficiaryId, paymentBeneficiaryDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Mark Beneficiary as Favorite",
            description = "Marks a beneficiary as favorite or removes favorite status."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully updated favorite status",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentBeneficiaryDTO.class))),
            @ApiResponse(responseCode = "404", description = "Beneficiary not found", content = @Content)
    })
    @PutMapping(value = "/{paymentBeneficiaryId}/favorite", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentBeneficiaryDTO>> markAsFavorite(
            @Parameter(description = "Payment Beneficiary ID", required = true)
            @PathVariable UUID paymentBeneficiaryId,
            @Parameter(description = "Is Favorite", required = true)
            @RequestParam Boolean isFavorite
    ) {
        return service.markAsFavorite(paymentBeneficiaryId, isFavorite)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Delete Beneficiary",
            description = "Deletes a beneficiary."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Successfully deleted beneficiary"),
            @ApiResponse(responseCode = "404", description = "Beneficiary not found", content = @Content)
    })
    @DeleteMapping(value = "/{paymentBeneficiaryId}")
    public Mono<ResponseEntity<Void>> deleteBeneficiary(
            @Parameter(description = "Payment Beneficiary ID", required = true)
            @PathVariable UUID paymentBeneficiaryId
    ) {
        return service.deleteBeneficiary(paymentBeneficiaryId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
