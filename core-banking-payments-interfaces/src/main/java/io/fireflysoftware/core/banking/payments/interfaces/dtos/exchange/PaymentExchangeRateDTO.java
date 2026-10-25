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


package io.fireflysoftware.core.banking.payments.interfaces.dtos.exchange;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.BaseDTO;
import org.fireflyframework.utils.annotations.FilterableId;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentExchangeRateDTO extends BaseDTO {

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID paymentExchangeRateId;

    @FilterableId
    @NotNull(message = "Payment order ID is required")
    private UUID paymentOrderId;

    @NotBlank(message = "Source currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Source currency must be a valid 3-letter ISO code")
    private String sourceCurrency;

    @NotBlank(message = "Target currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Target currency must be a valid 3-letter ISO code")
    private String targetCurrency;

    @NotNull(message = "Exchange rate is required")
    @DecimalMin(value = "0.0001", message = "Exchange rate must be positive")
    @Digits(integer = 10, fraction = 6, message = "Exchange rate must have at most 10 integer digits and 6 decimal places")
    private BigDecimal rate;

    @NotNull(message = "Rate date is required")
    private LocalDateTime rateDate;

    @NotBlank(message = "Rate provider is required")
    @Size(max = 100, message = "Rate provider must not exceed 100 characters")
    private String rateProvider;

    @NotBlank(message = "Rate type is required")
    @Pattern(regexp = "^(SPOT|FORWARD|FIXED)$",
             message = "Rate type must be SPOT, FORWARD, or FIXED")
    private String rateType; // 'SPOT', 'FORWARD', 'FIXED'

    @DecimalMin(value = "0.00", message = "Markup percentage must be non-negative")
    @DecimalMax(value = "100.00", message = "Markup percentage must not exceed 100%")
    @Digits(integer = 3, fraction = 4, message = "Markup percentage must have at most 3 integer digits and 4 decimal places")
    private BigDecimal markupPercentage;

    @NotNull(message = "Original amount is required")
    @DecimalMin(value = "0.01", message = "Original amount must be positive")
    @Digits(integer = 16, fraction = 2, message = "Original amount must have at most 16 integer digits and 2 decimal places")
    private BigDecimal originalAmount;

    @NotNull(message = "Converted amount is required")
    @DecimalMin(value = "0.01", message = "Converted amount must be positive")
    @Digits(integer = 16, fraction = 2, message = "Converted amount must have at most 16 integer digits and 2 decimal places")
    private BigDecimal convertedAmount;
}
