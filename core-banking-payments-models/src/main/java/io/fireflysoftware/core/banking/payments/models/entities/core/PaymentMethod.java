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

import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentPriorityEnum;
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
@Table("payment_method")
public class PaymentMethod extends BaseEntity {
    @Id
    @Column("payment_method_id")
    private UUID paymentMethodId;

    @Column("method_name")
    private String methodName;

    @Column("description")
    private String description;

    @Column("active_flag")
    private Boolean activeFlag;

    @Column("processing_time_hours")
    private Integer processingTimeHours;

    @Column("min_amount")
    private BigDecimal minAmount;

    @Column("max_amount")
    private BigDecimal maxAmount;

    @Column("supported_currencies")
    private String[] supportedCurrencies;

    @Column("requires_intermediary_bank")
    private Boolean requiresIntermediaryBank;

    @Column("requires_regulatory_reporting")
    private Boolean requiresRegulatoryReporting;

    @Column("requires_purpose_code")
    private Boolean requiresPurposeCode;

    @Column("default_priority")
    private PaymentPriorityEnum defaultPriority;
}
