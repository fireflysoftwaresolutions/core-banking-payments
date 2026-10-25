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


package io.fireflysoftware.core.banking.payments.interfaces.dtos.beneficiary;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.BaseDTO;
import io.fireflysoftware.core.banking.payments.interfaces.enums.beneficiary.BeneficiaryTypeEnum;
import org.fireflyframework.utils.annotations.FilterableId;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.*;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentBeneficiaryDTO extends BaseDTO {

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID paymentBeneficiaryId;

    @FilterableId
    @NotNull(message = "Payer account ID is required")
    private UUID payerAccountId;

    @NotBlank(message = "Beneficiary name is required")
    @Size(max = 255, message = "Beneficiary name must not exceed 255 characters")
    private String beneficiaryName;

    @NotNull(message = "Beneficiary type is required")
    private BeneficiaryTypeEnum beneficiaryType;

    @Size(max = 50, message = "Beneficiary account number must not exceed 50 characters")
    private String beneficiaryAccountNumber;

    @Pattern(regexp = "^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$",
             message = "Invalid IBAN format")
    private String beneficiaryIban;

    @Pattern(regexp = "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$",
             message = "Invalid BIC format")
    private String beneficiaryBic;

    @Size(max = 500, message = "Beneficiary address must not exceed 500 characters")
    private String beneficiaryAddress;

    @Size(max = 100, message = "Beneficiary city must not exceed 100 characters")
    private String beneficiaryCity;

    @Pattern(regexp = "^[A-Z0-9]{3,10}$", message = "Invalid postal code format")
    private String beneficiaryPostalCode;

    @Pattern(regexp = "^[A-Z]{2}$", message = "Country code must be a valid 2-letter ISO code")
    private String beneficiaryCountryCode;

    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String beneficiaryEmail;

    @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "Invalid phone number format")
    private String beneficiaryPhone;

    @Size(max = 50, message = "Tax ID must not exceed 50 characters")
    private String beneficiaryTaxId;

    @Size(max = 255, message = "Beneficiary bank name must not exceed 255 characters")
    private String beneficiaryBankName;

    @Size(max = 500, message = "Beneficiary bank address must not exceed 500 characters")
    private String beneficiaryBankAddress;

    @Pattern(regexp = "^[A-Z]{2}$", message = "Bank country code must be a valid 2-letter ISO code")
    private String beneficiaryBankCountryCode;

    @NotNull(message = "Is favorite flag is required")
    private Boolean isFavorite;

    @Size(max = 100, message = "Nickname must not exceed 100 characters")
    private String nickname;
}