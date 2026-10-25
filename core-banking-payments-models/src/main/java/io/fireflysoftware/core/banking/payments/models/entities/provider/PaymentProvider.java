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


package io.fireflysoftware.core.banking.payments.models.entities.provider;

import io.fireflysoftware.core.banking.payments.interfaces.enums.provider.ProviderStatusEnum;
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
@Table("payment_provider")
public class PaymentProvider extends BaseEntity {

    @Id
    @Column("payment_provider_id")
    private UUID paymentProviderId;

    @Column("payment_order_id")
    private UUID paymentOrderId;

    @Column("provider_name")
    private String providerName;

    @Column("external_reference")
    private String externalReference;

    @Column("status")
    private ProviderStatusEnum status;

    @Column("provider_type")
    private String providerType;

    @Column("provider_url")
    private String providerUrl;

    @Column("provider_api_key")
    private String providerApiKey;

    @Column("provider_username")
    private String providerUsername;

    @Column("provider_account_id")
    private String providerAccountId;

    @Column("provider_fee")
    private BigDecimal providerFee;

    @Column("provider_fee_currency_code")
    private String providerFeeCurrencyCode;

    @Column("provider_response_code")
    private String providerResponseCode;

    @Column("provider_response_message")
    private String providerResponseMessage;

    @Column("provider_transaction_id")
    private String providerTransactionId;
}
