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


package io.fireflysoftware.core.banking.payments.web.controllers.exchange;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.banking.payments.core.services.exchange.PaymentExchangeRateManagerService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.exchange.PaymentExchangeRateDTO;
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
@Tag(name = "Payment Exchange Rates", description = "APIs for managing payment exchange rates")
@RestController
public class PaymentExchangeRateManagerController {

    @Autowired
    private PaymentExchangeRateManagerService service;

    @ModelAttribute("filterRequest")
    public FilterRequest<PaymentExchangeRateDTO> exchangeRateFilterRequest() {
        FilterRequest<PaymentExchangeRateDTO> request = new FilterRequest<>();
        request.setFilters(new PaymentExchangeRateDTO());
        return request;
    }

    @Operation(
            summary = "Retrieve Exchange Rates by Payment Order ID",
            description = "Retrieves a paginated list of exchange rates for a specific payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved exchange rates",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaginationResponse.class))),
            @ApiResponse(responseCode = "404", description = "No exchange rates found", content = @Content)
    })
    @GetMapping(value = "/api/v1/payment-orders/{paymentOrderId}/exchange-rates", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaginationResponse<PaymentExchangeRateDTO>>> getExchangeRatesByPaymentOrderId(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @ParameterObject
            @ModelAttribute("filterRequest") FilterRequest<PaymentExchangeRateDTO> filterRequest
    ) {
        return service.getExchangeRatesByPaymentOrderId(paymentOrderId, filterRequest)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create Exchange Rate",
            description = "Creates a new exchange rate for a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Successfully created exchange rate",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentExchangeRateDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input", content = @Content)
    })
    @PostMapping(value = "/api/v1/payment-orders/{paymentOrderId}/exchange-rates", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentExchangeRateDTO>> createExchangeRate(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @RequestBody PaymentExchangeRateDTO paymentExchangeRateDTO
    ) {
        return service.createExchangeRate(paymentOrderId, paymentExchangeRateDTO)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @Operation(
            summary = "Get Exchange Rate by ID",
            description = "Retrieves a specific exchange rate by its ID."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved exchange rate",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentExchangeRateDTO.class))),
            @ApiResponse(responseCode = "404", description = "Exchange rate not found", content = @Content)
    })
    @GetMapping(value = "/api/v1/payment-orders/{paymentOrderId}/exchange-rates/{paymentExchangeRateId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentExchangeRateDTO>> getExchangeRateById(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @Parameter(description = "Payment Exchange Rate ID", required = true)
            @PathVariable UUID paymentExchangeRateId
    ) {
        return service.getExchangeRateById(paymentOrderId, paymentExchangeRateId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Update Exchange Rate",
            description = "Updates an exchange rate for a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully updated exchange rate",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentExchangeRateDTO.class))),
            @ApiResponse(responseCode = "404", description = "Exchange rate not found", content = @Content)
    })
    @PutMapping(value = "/api/v1/payment-orders/{paymentOrderId}/exchange-rates/{paymentExchangeRateId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentExchangeRateDTO>> updateExchangeRate(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @Parameter(description = "Payment Exchange Rate ID", required = true)
            @PathVariable UUID paymentExchangeRateId,
            @RequestBody PaymentExchangeRateDTO paymentExchangeRateDTO
    ) {
        return service.updateExchangeRate(paymentOrderId, paymentExchangeRateId, paymentExchangeRateDTO)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Delete Exchange Rate",
            description = "Deletes an exchange rate for a payment order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Successfully deleted exchange rate"),
            @ApiResponse(responseCode = "404", description = "Exchange rate not found", content = @Content)
    })
    @DeleteMapping(value = "/api/v1/payment-orders/{paymentOrderId}/exchange-rates/{paymentExchangeRateId}")
    public Mono<ResponseEntity<Void>> deleteExchangeRate(
            @Parameter(description = "Payment Order ID", required = true)
            @PathVariable UUID paymentOrderId,
            @Parameter(description = "Payment Exchange Rate ID", required = true)
            @PathVariable UUID paymentExchangeRateId
    ) {
        return service.deleteExchangeRate(paymentOrderId, paymentExchangeRateId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @Operation(
            summary = "Get Current Exchange Rate",
            description = "Retrieves the current exchange rate for a currency pair."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved exchange rate",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PaymentExchangeRateDTO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input", content = @Content)
    })
    @GetMapping(value = "/api/v1/exchange-rates", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaymentExchangeRateDTO>> getCurrentExchangeRate(
            @Parameter(description = "Source Currency", required = true)
            @RequestParam String sourceCurrency,
            @Parameter(description = "Target Currency", required = true)
            @RequestParam String targetCurrency
    ) {
        return service.getCurrentExchangeRate(sourceCurrency, targetCurrency)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
