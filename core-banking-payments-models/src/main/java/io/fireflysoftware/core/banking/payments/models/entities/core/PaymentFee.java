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


package io.fireflysoftware.core.banking.payments.models.entities.core;

import io.fireflysoftware.core.banking.payments.interfaces.enums.fee.FeeTypeEnum;
import io.fireflysoftware.core.banking.payments.models.entities.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("payment_fee")
public class PaymentFee extends BaseEntity {
    @Id
    @Column("payment_fee_id")
    private UUID paymentFeeId;

    @Column("payment_order_id")
    private UUID paymentOrderId;

    @Column("fee_type")
    private String feeType;

    @Column("fee_type_enum")
    private FeeTypeEnum feeTypeEnum;

    @Column("fee_amount")
    private BigDecimal feeAmount;

    @Column("fee_currency_code")
    private String feeCurrencyCode;

    @Column("fee_description")
    private String feeDescription;

    @Column("fee_calculation_method")
    private String feeCalculationMethod;

    @Column("fee_rate")
    private BigDecimal feeRate;

    @Column("fee_tax_amount")
    private BigDecimal feeTaxAmount;

    @Column("fee_tax_rate")
    private BigDecimal feeTaxRate;

    @Column("fee_tax_type")
    private String feeTaxType;

    @Column("fee_waived")
    private Boolean feeWaived;
}
