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


package io.fireflysoftware.core.banking.payments.models.entities.exchange;

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
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("payment_exchange_rate")
public class PaymentExchangeRate extends BaseEntity {

    @Id
    @Column("payment_exchange_rate_id")
    private UUID paymentExchangeRateId;

    @Column("payment_order_id")
    private UUID paymentOrderId;

    @Column("source_currency")
    private String sourceCurrency;

    @Column("target_currency")
    private String targetCurrency;

    @Column("rate")
    private BigDecimal rate;

    @Column("rate_date")
    private LocalDateTime rateDate;

    @Column("rate_provider")
    private String rateProvider;

    @Column("rate_type")
    private String rateType; // 'SPOT', 'FORWARD', 'FIXED'

    @Column("markup_percentage")
    private BigDecimal markupPercentage;

    @Column("original_amount")
    private BigDecimal originalAmount;

    @Column("converted_amount")
    private BigDecimal convertedAmount;
}
