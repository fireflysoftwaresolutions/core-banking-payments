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
import io.fireflysoftware.core.banking.payments.interfaces.enums.beneficiary.BeneficiaryTypeEnum;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentCategoryEnum;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentChannelEnum;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentPriorityEnum;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentPurposeEnum;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentStatusEnum;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentTypeEnum;
import org.fireflyframework.utils.annotations.FilterableId;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOrderDTO extends BaseDTO {

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID paymentOrderId;

    @FilterableId
    @NotNull(message = "Payer account ID is required")
    private UUID payerAccountId;

    @FilterableId
    @NotNull(message = "Payment method ID is required")
    private UUID paymentMethodId;

    @FilterableId
    private UUID paymentBeneficiaryId;

    @NotBlank(message = "Beneficiary name is required")
    @Size(max = 255, message = "Beneficiary name must not exceed 255 characters")
    private String beneficiaryName;

    @Size(max = 50, message = "Beneficiary account number must not exceed 50 characters")
    private String beneficiaryAccountNumber;

    @Pattern(regexp = "^[A-Z]{2}[0-9]{2}[A-Z0-9]{4}[0-9]{7}([A-Z0-9]?){0,16}$",
             message = "Invalid IBAN format")
    private String beneficiaryIban;

    @Pattern(regexp = "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$",
             message = "Invalid BIC format")
    private String beneficiaryBic;

    @Size(max = 500, message = "Beneficiary address must not exceed 500 characters")
    private String beneficiaryAddress;

    @Pattern(regexp = "^[A-Z]{2}$", message = "Country code must be a valid 2-letter ISO code")
    private String beneficiaryCountryCode;

    @Pattern(regexp = "^[0-9]{9}$", message = "ABA routing number must be 9 digits")
    private String abaRoutingNumber;

    // e.g. "SEPA_SCT", "SWIFT", etc.
    @NotNull(message = "Payment type is required")
    private PaymentTypeEnum paymentType;

    @NotNull(message = "Order date is required")
    private LocalDateTime orderDate;

    // e.g. "Initiated", "Completed", "Failed"
    @NotNull(message = "Payment status is required")
    private PaymentStatusEnum status;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @Digits(integer = 16, fraction = 2, message = "Amount must have at most 16 integer digits and 2 decimal places")
    private BigDecimal amount;

    @NotBlank(message = "Currency code is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency code must be a valid 3-letter ISO code")
    private String currencyCode;

    @Pattern(regexp = "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$",
             message = "Invalid SWIFT BIC format")
    private String swiftBicCode;

    @Size(max = 140, message = "Remittance information must not exceed 140 characters")
    private String remittanceInformation;

    private UUID documentId;

    @Size(max = 35, message = "Reference number must not exceed 35 characters")
    private String referenceNumber;

    @Size(max = 35, message = "End-to-end ID must not exceed 35 characters")
    private String endToEndId;

    private PaymentPriorityEnum priority;
    private PaymentPurposeEnum purpose;

    @Size(max = 10, message = "Purpose code must not exceed 10 characters")
    private String purposeCode;

    private PaymentChannelEnum channel;
    private PaymentCategoryEnum category;

    @DecimalMin(value = "0.0001", message = "Exchange rate must be positive")
    @Digits(integer = 10, fraction = 6, message = "Exchange rate must have at most 10 integer digits and 6 decimal places")
    private BigDecimal exchangeRate;

    @Pattern(regexp = "^(OUR|BEN|SHA)$", message = "Charge bearer must be OUR, BEN, or SHA")
    private String chargeBearer;

    private BeneficiaryTypeEnum beneficiaryType;

    @Size(max = 255, message = "Beneficiary bank name must not exceed 255 characters")
    private String beneficiaryBankName;

    @Size(max = 500, message = "Beneficiary bank address must not exceed 500 characters")
    private String beneficiaryBankAddress;

    @Pattern(regexp = "^[A-Z]{2}$", message = "Beneficiary bank country code must be a valid 2-letter ISO code")
    private String beneficiaryBankCountryCode;

    @Size(max = 255, message = "Intermediary bank name must not exceed 255 characters")
    private String intermediaryBankName;

    @Pattern(regexp = "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$",
             message = "Invalid intermediary bank BIC format")
    private String intermediaryBankBic;

    @Size(max = 50, message = "Intermediary bank account must not exceed 50 characters")
    private String intermediaryBankAccount;

    @Size(max = 1000, message = "Regulatory reporting must not exceed 1000 characters")
    private String regulatoryReporting;

    @Size(max = 500, message = "Tax information must not exceed 500 characters")
    private String taxInformation;

    @Size(max = 50, message = "Batch ID must not exceed 50 characters")
    private String batchId;

    @Future(message = "Value date must be in the future")
    private LocalDate valueDate;

    @Future(message = "Requested execution date must be in the future")
    private LocalDate requestedExecutionDate;
}
