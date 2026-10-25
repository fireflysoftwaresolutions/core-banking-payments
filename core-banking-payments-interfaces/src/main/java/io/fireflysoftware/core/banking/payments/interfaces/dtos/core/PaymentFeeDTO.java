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


package io.fireflysoftware.core.banking.payments.interfaces.dtos.core;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.BaseDTO;
import io.fireflysoftware.core.banking.payments.interfaces.enums.fee.FeeTypeEnum;
import org.fireflyframework.utils.annotations.FilterableId;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFeeDTO extends BaseDTO {

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID paymentFeeId;

    @FilterableId
    @NotNull(message = "Payment order ID is required")
    private UUID paymentOrderId;

    @Size(max = 50, message = "Fee type must not exceed 50 characters")
    private String feeType;

    @NotNull(message = "Fee type enum is required")
    private FeeTypeEnum feeTypeEnum;

    @NotNull(message = "Fee amount is required")
    @DecimalMin(value = "0.00", message = "Fee amount must be non-negative")
    @Digits(integer = 16, fraction = 2, message = "Fee amount must have at most 16 integer digits and 2 decimal places")
    private BigDecimal feeAmount;

    @NotBlank(message = "Fee currency code is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Fee currency code must be a valid 3-letter ISO code")
    private String feeCurrencyCode;

    @Size(max = 255, message = "Fee description must not exceed 255 characters")
    private String feeDescription;

    @Size(max = 100, message = "Fee calculation method must not exceed 100 characters")
    private String feeCalculationMethod;

    @DecimalMin(value = "0.00", message = "Fee rate must be non-negative")
    @DecimalMax(value = "100.00", message = "Fee rate must not exceed 100%")
    @Digits(integer = 3, fraction = 4, message = "Fee rate must have at most 3 integer digits and 4 decimal places")
    private BigDecimal feeRate;

    @DecimalMin(value = "0.00", message = "Fee tax amount must be non-negative")
    @Digits(integer = 16, fraction = 2, message = "Fee tax amount must have at most 16 integer digits and 2 decimal places")
    private BigDecimal feeTaxAmount;

    @DecimalMin(value = "0.00", message = "Fee tax rate must be non-negative")
    @DecimalMax(value = "100.00", message = "Fee tax rate must not exceed 100%")
    @Digits(integer = 3, fraction = 4, message = "Fee tax rate must have at most 3 integer digits and 4 decimal places")
    private BigDecimal feeTaxRate;

    @Size(max = 50, message = "Fee tax type must not exceed 50 characters")
    private String feeTaxType;

    @NotNull(message = "Fee waived flag is required")
    private Boolean feeWaived;
}
