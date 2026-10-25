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


package io.fireflysoftware.core.banking.payments.models.repositories.exchange;

import io.fireflysoftware.core.banking.payments.models.entities.exchange.PaymentExchangeRate;
import io.fireflysoftware.core.banking.payments.models.repositories.BaseRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface PaymentExchangeRateRepository extends BaseRepository<PaymentExchangeRate, UUID> {
    Mono<PaymentExchangeRate> findByPaymentExchangeRateIdAndPaymentOrderId(UUID paymentExchangeRateId, UUID paymentOrderId);

    @Query("SELECT * FROM payment_exchange_rate WHERE payment_order_id=:parent AND payment_exchange_rate_id=:id FOR UPDATE")
    Mono<PaymentExchangeRate> findScopedForUpdate(@Param("parent") UUID paymentOrderId, @Param("id") UUID paymentExchangeRateId);

    Flux<PaymentExchangeRate> findByPaymentOrderId(UUID paymentOrderId);
    Mono<PaymentExchangeRate> findFirstByPaymentOrderIdOrderByDateCreatedDesc(UUID paymentOrderId);
    Mono<PaymentExchangeRate> findFirstBySourceCurrencyAndTargetCurrencyOrderByRateDateDesc(
            String sourceCurrency, String targetCurrency);
}
