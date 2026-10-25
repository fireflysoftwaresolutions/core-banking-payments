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

package io.fireflysoftware.core.banking.payments.core.services.closure.impl;

import io.fireflysoftware.core.banking.payments.core.services.closure.*;

import static io.fireflysoftware.core.banking.payments.interfaces.dtos.closure.PaymentAccountQuiescenceContracts.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PaymentAccountQuiescenceCanonical {
    private PaymentAccountQuiescenceCanonical() {}

    public static String canonical(Selection selected, Quiesce input) {
        PaymentAccountQuiescenceRules.selection(selected);
        PaymentAccountQuiescenceRules.basic(input);
        if (!selected.enrollmentId().equals(input.enrollmentId()))
            throw PaymentAccountQuiescenceRules.bad();
        var value = new StringBuilder("PAYMENT_ACCOUNT_QUIESCENCE_V1\n");
        for (String field :
                new String[] {
                    selected.tenantId(),
                    selected.accountId().toString(),
                    selected.enrollmentId().toString(),
                    selected.contractId().toString(),
                    selected.currency(),
                    selected.currencyScale().toString(),
                    selected.currencyPolicy(),
                    input.actionId().toString(),
                    input.closureId().toString(),
                    input.enrollmentId().toString(),
                    input.expectedFenceVersion().toString()
                })
            value.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
        return value.toString();
    }

    public static String digest(Selection selected, Quiesce input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            canonical(selected, input)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static String releaseDigest(Selection selected, Release input) {
        PaymentAccountQuiescenceRules.selection(selected);
        PaymentAccountQuiescenceRules.release(input);
        String retained =
                canonical(
                        selected,
                        new Quiesce(
                                input.actionId(),
                                input.closureId(),
                                selected.enrollmentId(),
                                input.expectedFenceVersion()));
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            ("PAYMENT_ACCOUNT_RELEASE_V1\n" + retained)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
