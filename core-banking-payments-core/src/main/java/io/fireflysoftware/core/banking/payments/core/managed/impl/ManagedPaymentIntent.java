/*
 * Copyright 2026 Firefly Software Foundation
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

package io.fireflysoftware.core.banking.payments.core.managed.impl;

import io.fireflysoftware.core.banking.payments.core.managed.*;

import static io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicy.fail;

import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.EnrolledPair;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.Create;

import java.math.*;
import java.util.*;

public final class ManagedPaymentIntent {
    private ManagedPaymentIntent() {}

    public record CanonicalIntent(
            Create normalizedRequest,
            String fixedScaleAmount,
            int currencyScale,
            String currencyPolicy,
            String requestDigest) {}

    public static void basic(Create input) {
        if (input == null
                || input.actionId() == null
                || input.sourceAccountId() == null
                || input.destinationAccountId() == null
                || input.sourceAccountId().equals(input.destinationAccountId())
                || input.amount() == null
                || input.amount().signum() <= 0
                || input.currency() == null
                || !input.currency().matches("[A-Za-z]{3}")
                || input.ttlSeconds() < 1
                || input.ttlSeconds() > 900)
            throw fail(400, "EXACT_INTERNAL_PAYMENT_INSTRUCTION_REQUIRED");
    }

    public static CanonicalIntent canonical(Create input, EnrolledPair pair) {
        basic(input);
        String currency = input.currency().toUpperCase(Locale.ROOT);
        if (!currency.equals(pair.currency())
                || pair.currencyScale() < 0
                || pair.currencyScale() > 18) throw fail(400, "ENROLLED_CURRENCY_POLICY_REQUIRED");
        if (input.amount().compareTo(BigDecimal.TEN.pow(20)) >= 0)
            throw fail(400, "PAYMENT_NUMERIC_RANGE_EXCEEDED");
        if (input.amount().stripTrailingZeros().scale() > pair.currencyScale())
            throw fail(400, "EXACT_ENROLLED_CURRENCY_PRECISION_REQUIRED");
        BigDecimal amount;
        try {
            amount = input.amount().setScale(pair.currencyScale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw fail(400, "EXACT_ENROLLED_CURRENCY_PRECISION_REQUIRED");
        }
        if (amount.signum() <= 0 || amount.compareTo(BigDecimal.TEN.pow(20)) >= 0)
            throw fail(400, "PAYMENT_NUMERIC_RANGE_EXCEEDED");
        var normalized =
                new Create(
                        input.actionId(),
                        input.sourceAccountId(),
                        input.destinationAccountId(),
                        amount,
                        currency,
                        input.ttlSeconds(),
                        input.customerNotAfterEpochMillis());
        String exact =
                "managed-internal-payment-v1|"
                        + normalized.actionId()
                        + "|"
                        + normalized.sourceAccountId()
                        + "|"
                        + normalized.destinationAccountId()
                        + "|"
                        + amount.toPlainString()
                        + "|"
                        + currency
                        + "|"
                        + normalized.ttlSeconds()
                        + "|"
                        + normalized.customerNotAfterEpochMillis();
        try {
            return new CanonicalIntent(
                    normalized,
                    amount.toPlainString(),
                    pair.currencyScale(),
                    pair.currencyPolicy(),
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    exact.getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8))));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
