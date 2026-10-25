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

import io.fireflysoftware.core.banking.payments.sdk.api.PaymentExchangeRatesApi;
import io.fireflysoftware.core.banking.payments.sdk.api.PaymentFeesApi;
import io.fireflysoftware.core.banking.payments.sdk.invoker.ApiClient;
import io.fireflysoftware.core.banking.payments.sdk.model.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Genuine frozen SDK consumer for the legacy child-parent contract, not financial execution. */
public class PaymentOrderChildParentageSdkProbe {
  static final Duration TIMEOUT = Duration.ofSeconds(15);
  static int checks;

  static UUID id(String name) {
    return UUID.fromString(System.getenv(name));
  }

  static void check(boolean ok, String description) {
    if (!ok) throw new AssertionError(description);
    checks++;
    System.out.println("CHILD_SDK_CHECK=" + description);
  }

  static void absent(Runnable request) {
    try {
      request.run();
      throw new AssertionError("Expected 404");
    } catch (WebClientResponseException error) {
      check(error.getStatusCode().value() == 404, "Wrong or missing parent returns 404");
    }
  }

  static PaymentExchangeRateDTO fx(UUID parent) {
    return new PaymentExchangeRateDTO()
        .paymentOrderId(parent)
        .sourceCurrency("USD")
        .targetCurrency("EUR")
        .rate(new BigDecimal("0.95"))
        .rateDate(LocalDateTime.of(2026, 10, 1, 0, 0))
        .rateProvider("Synthetic SDK provider")
        .rateType("SPOT")
        .originalAmount(new BigDecimal("100.00"))
        .convertedAmount(new BigDecimal("95.00"));
  }

  public static void main(String[] args) {
    var client = new ApiClient().setBasePath(System.getenv("OWNED_PAYMENTS_URL"));
    var rates = new PaymentExchangeRatesApi(client);
    var fees = new PaymentFeesApi(client);
    UUID a = id("OWNED_PARENT_A"),
        b = id("OWNED_PARENT_B"),
        foreign = id("OWNED_FX_B"),
        legacy = id("OWNED_FEE_A");
    var page =
        rates
            .getExchangeRatesByPaymentOrderId(a, 0, 1, null, null, null, null, null)
            .block(TIMEOUT);
    check(
        page.getTotalElements() == 2 && page.getContent().size() == 1,
        "FX count is scoped before pagination");
    check(
        ((Map<?, ?>) page.getContent().getFirst()).get("paymentOrderId").equals(a.toString()),
        "FX page contains route parent only");
    var feePage = fees.getAllPaymentFees(a, 0, 1, null, null, null, null, null).block(TIMEOUT);
    check(
        feePage.getTotalElements() == 2 && feePage.getContent().size() == 1,
        "Fee count is scoped before pagination");
    check(
        ((Map<?, ?>) feePage.getContent().getFirst()).get("paymentOrderId").equals(a.toString()),
        "Fee page contains route parent only");
    var old = fees.getPaymentFeeById(a, legacy, null).block(TIMEOUT);
    check(
        old.getFeeTypeEnum() == null && old.getFeeType().equals("LEGACY_UNCLASSIFIED"),
        "Legacy free-text classification survives without inferred enum");
    absent(() -> rates.getExchangeRateById(a, foreign, null).block(TIMEOUT));
    absent(() -> rates.updateExchangeRate(a, foreign, fx(a), null).block(TIMEOUT));
    absent(() -> rates.deleteExchangeRate(a, foreign, null).block(TIMEOUT));
    var retained = rates.getExchangeRateById(b, foreign, null).block(TIMEOUT);
    check(
        retained.getPaymentOrderId().equals(b)
            && retained.getRateProvider().equals("Synthetic original"),
        "Rejected foreign mutations leave original row unchanged");
    var made = rates.createExchangeRate(a, fx(b), null).block(TIMEOUT);
    check(
        made.getPaymentOrderId().equals(a) && made.getPaymentExchangeRateId() != null,
        "FX create uses route parent and server identity");
    var changed =
        rates
            .updateExchangeRate(
                a, made.getPaymentExchangeRateId(), fx(b).rateProvider("Synthetic changed"), null)
            .block(TIMEOUT);
    check(
        changed.getPaymentOrderId().equals(a)
            && changed.getDateCreated().equals(made.getDateCreated()),
        "FX update retains stored parent and creation timestamp");
    rates.deleteExchangeRate(a, made.getPaymentExchangeRateId(), null).block(TIMEOUT);
    absent(
        () -> rates.getExchangeRateById(a, made.getPaymentExchangeRateId(), null).block(TIMEOUT));
    var input =
        new PaymentFeeDTO()
            .paymentOrderId(b)
            .feeType("Explicit SDK legacy label")
            .feeTypeEnum(PaymentFeeDTO.FeeTypeEnumEnum.TRANSACTION_FEE)
            .feeAmount(new BigDecimal("2.25"))
            .feeCurrencyCode("USD")
            .feeWaived(false);
    var fee = fees.createPaymentFee(a, null, input).block(TIMEOUT);
    check(
        fee.getPaymentOrderId().equals(a) && fee.getPaymentFeeId() != null,
        "Fee create uses route parent and server identity");
    check(
        fee.getFeeTypeEnum() == PaymentFeeDTO.FeeTypeEnumEnum.TRANSACTION_FEE
            && fee.getFeeType().equals("Explicit SDK legacy label"),
        "Typed enum and independent legacy label roundtrip through SDK");
    var updated =
        fees.updatePaymentFee(
                a, fee.getPaymentFeeId(), null, input.feeAmount(new BigDecimal("3.25")))
            .block(TIMEOUT);
    check(
        updated.getPaymentOrderId().equals(a)
            && updated.getDateCreated().equals(fee.getDateCreated()),
        "Fee update retains route parent and stored creation timestamp");
    check(
        fees.getPaymentFeeById(a, fee.getPaymentFeeId(), null)
                .block(TIMEOUT)
                .getFeeAmount()
                .compareTo(new BigDecimal("3.25"))
            == 0,
        "Typed fee update persists exact amount");
    absent(() -> fees.getPaymentFeeById(b, fee.getPaymentFeeId(), null).block(TIMEOUT));
    absent(() -> fees.deletePaymentFee(b, fee.getPaymentFeeId(), null).block(TIMEOUT));
    check(
        fees.getPaymentFeeById(a, fee.getPaymentFeeId(), null)
                .block(TIMEOUT)
                .getFeeAmount()
                .compareTo(new BigDecimal("3.25"))
            == 0,
        "Wrong-parent fee delete leaves original row unchanged");
    System.out.println("CHILD_SDK_ASSERTIONS=" + checks);
  }
}
