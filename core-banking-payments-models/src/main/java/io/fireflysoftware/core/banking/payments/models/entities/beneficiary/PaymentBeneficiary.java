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


package io.fireflysoftware.core.banking.payments.models.entities.beneficiary;

import io.fireflysoftware.core.banking.payments.interfaces.enums.beneficiary.BeneficiaryTypeEnum;
import io.fireflysoftware.core.banking.payments.models.entities.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("payment_beneficiary")
public class PaymentBeneficiary extends BaseEntity {

    @Id
    @Column("payment_beneficiary_id")
    private UUID paymentBeneficiaryId;

    @Column("payer_account_id")
    private UUID payerAccountId;

    @Column("beneficiary_name")
    private String beneficiaryName;

    @Column("beneficiary_type")
    private BeneficiaryTypeEnum beneficiaryType;

    @Column("beneficiary_account_number")
    private String beneficiaryAccountNumber;

    @Column("beneficiary_iban")
    private String beneficiaryIban;

    @Column("beneficiary_bic")
    private String beneficiaryBic;

    @Column("beneficiary_address")
    private String beneficiaryAddress;

    @Column("beneficiary_city")
    private String beneficiaryCity;

    @Column("beneficiary_postal_code")
    private String beneficiaryPostalCode;

    @Column("beneficiary_country_code")
    private String beneficiaryCountryCode;

    @Column("beneficiary_email")
    private String beneficiaryEmail;

    @Column("beneficiary_phone")
    private String beneficiaryPhone;

    @Column("beneficiary_tax_id")
    private String beneficiaryTaxId;

    @Column("beneficiary_bank_name")
    private String beneficiaryBankName;

    @Column("beneficiary_bank_address")
    private String beneficiaryBankAddress;

    @Column("beneficiary_bank_country_code")
    private String beneficiaryBankCountryCode;

    @Column("is_favorite")
    private Boolean isFavorite;

    @Column("nickname")
    private String nickname;
}
