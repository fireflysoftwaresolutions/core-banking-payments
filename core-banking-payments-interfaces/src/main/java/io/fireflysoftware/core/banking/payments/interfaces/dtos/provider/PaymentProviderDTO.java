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


package io.fireflysoftware.core.banking.payments.interfaces.dtos.provider;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.BaseDTO;
import io.fireflysoftware.core.banking.payments.interfaces.enums.provider.ProviderStatusEnum;
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
public class PaymentProviderDTO extends BaseDTO {

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID paymentProviderId;

    @FilterableId
    @NotNull(message = "Payment order ID is required")
    private UUID paymentOrderId;

    @NotBlank(message = "Provider name is required")
    @Size(max = 255, message = "Provider name must not exceed 255 characters")
    private String providerName;

    @Size(max = 100, message = "External reference must not exceed 100 characters")
    private String externalReference;

    // e.g. "ACTIVE", "INACTIVE", "PENDING", "SUSPENDED"
    @NotNull(message = "Provider status is required")
    private ProviderStatusEnum status;

    @NotBlank(message = "Provider type is required")
    @Size(max = 50, message = "Provider type must not exceed 50 characters")
    private String providerType;

    @Pattern(regexp = "^https?://.*", message = "Provider URL must be a valid HTTP/HTTPS URL")
    @Size(max = 500, message = "Provider URL must not exceed 500 characters")
    private String providerUrl;

    @Size(max = 255, message = "Provider API key must not exceed 255 characters")
    private String providerApiKey;

    @Size(max = 100, message = "Provider username must not exceed 100 characters")
    private String providerUsername;

    @Size(max = 100, message = "Provider account ID must not exceed 100 characters")
    private String providerAccountId;

    @DecimalMin(value = "0.00", message = "Provider fee must be non-negative")
    @Digits(integer = 16, fraction = 2, message = "Provider fee must have at most 16 integer digits and 2 decimal places")
    private BigDecimal providerFee;

    @Pattern(regexp = "^[A-Z]{3}$", message = "Provider fee currency code must be a valid 3-letter ISO code")
    private String providerFeeCurrencyCode;

    @Size(max = 20, message = "Provider response code must not exceed 20 characters")
    private String providerResponseCode;

    @Size(max = 500, message = "Provider response message must not exceed 500 characters")
    private String providerResponseMessage;

    @Size(max = 100, message = "Provider transaction ID must not exceed 100 characters")
    private String providerTransactionId;
}
