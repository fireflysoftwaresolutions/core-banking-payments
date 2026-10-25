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

import static io.fireflysoftware.core.banking.payments.interfaces.dtos.closure.PaymentAccountQuiescenceContracts.*;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;

class PaymentAccountQuiescenceRulesTest {
    static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ENROLLMENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID CONTRACT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID ACTION = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final UUID CLOSURE = UUID.fromString("00000000-0000-0000-0000-000000000005");
    static final Selection SELECTED =
            new Selection("tenant-one", ACCOUNT, ENROLLMENT, CONTRACT, "USD", 2, "funds-policy-v1");
    static final String WIRE =
            "{\"actionId\":\""
                    + ACTION
                    + "\",\"closureId\":\""
                    + CLOSURE
                    + "\",\"enrollmentId\":\""
                    + ENROLLMENT
                    + "\",\"expectedFenceVersion\":0}";

    static Quiesce request() {
        return new Quiesce(ACTION, CLOSURE, ENROLLMENT, 0L);
    }

    static Quiesce decode(String wire) {
        return PaymentAccountQuiescenceRules.decode(wire.getBytes(StandardCharsets.UTF_8));
    }

    static void bad(String wire) {
        assertThatThrownBy(() -> decode(wire))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void exactRequestRequiresCurrentFenceVersionAndSelectedEnrollment() {
        assertThat(decode(WIRE)).isEqualTo(request());
        assertThatCode(() -> PaymentAccountQuiescenceRules.admit(SELECTED, request(), 0))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PaymentAccountQuiescenceRules.admit(SELECTED, request(), 1))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(
                        () ->
                                PaymentAccountQuiescenceRules.admit(
                                        new Selection(
                                                "tenant-one",
                                                ACCOUNT,
                                                CONTRACT,
                                                CONTRACT,
                                                "USD",
                                                2,
                                                "funds-policy-v1"),
                                        request(),
                                        0))
                .isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clear", "amount", "pendingCount", "actor", "tenant", "forceClose"})
    void rejectsCallerAbsenceAndAuthorityAssertions(String field) {
        bad(WIRE.substring(0, WIRE.length() - 1) + ",\"" + field + "\":true}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"-0", "-1", "0.0", "0e0", "\"0\"", "null", "true", "9223372036854775808"})
    void rejectsNoncanonicalFenceVersion(String token) {
        bad(WIRE.replace("\"expectedFenceVersion\":0", "\"expectedFenceVersion\":" + token));
    }

    @Test
    void rejectsDuplicateMissingTrailingAndMalformedInput() {
        bad(WIRE.replace("\"closureId\":", "\"closureId\":\"" + ACTION + "\",\"closureId\":"));
        bad(WIRE.replace("\"enrollmentId\":\"" + ENROLLMENT + "\",", ""));
        bad(WIRE + "null");
        bad("[]");
        assertThatThrownBy(
                        () ->
                                PaymentAccountQuiescenceRules.decode(
                                        new byte[] {(byte) 0xc3, (byte) 0x28}))
                .isInstanceOf(ResponseStatusException.class);
        bad(WIRE.replace(ACTION.toString(), "1"));
    }

    @Test
    void canonicalIdentityBindsBothOwnerSelectionAndCommand() {
        String expected =
                "PAYMENT_ACCOUNT_QUIESCENCE_V1\n10:tenant-one36:"
                        + ACCOUNT
                        + "36:"
                        + ENROLLMENT
                        + "36:"
                        + CONTRACT
                        + "3:USD1:215:funds-policy-v136:"
                        + ACTION
                        + "36:"
                        + CLOSURE
                        + "36:"
                        + ENROLLMENT
                        + "1:0";
        assertThat(PaymentAccountQuiescenceCanonical.canonical(SELECTED, request()))
                .isEqualTo(expected);
        assertThat(
                        PaymentAccountQuiescenceCanonical.digest(
                                SELECTED, new Quiesce(CONTRACT, CLOSURE, ENROLLMENT, 0L)))
                .isNotEqualTo(PaymentAccountQuiescenceCanonical.digest(SELECTED, request()));
        assertThat(
                        PaymentAccountQuiescenceCanonical.digest(
                                SELECTED, new Quiesce(ACTION, CONTRACT, ENROLLMENT, 0L)))
                .isNotEqualTo(PaymentAccountQuiescenceCanonical.digest(SELECTED, request()));
        assertThat(
                        PaymentAccountQuiescenceCanonical.digest(
                                SELECTED, new Quiesce(ACTION, CLOSURE, ENROLLMENT, 1L)))
                .isNotEqualTo(PaymentAccountQuiescenceCanonical.digest(SELECTED, request()));
        assertThat(
                        PaymentAccountQuiescenceCanonical.digest(
                                new Selection(
                                        "tenant-two",
                                        ACCOUNT,
                                        ENROLLMENT,
                                        CONTRACT,
                                        "USD",
                                        2,
                                        "funds-policy-v1"),
                                request()))
                .isNotEqualTo(PaymentAccountQuiescenceCanonical.digest(SELECTED, request()));
    }

    @Test
    void typedQuiescenceCannotCoerceMissingIdentityOrVersion() {
        for (Quiesce next :
                List.of(
                        new Quiesce(null, CLOSURE, ENROLLMENT, 0L),
                        new Quiesce(ACTION, null, ENROLLMENT, 0L),
                        new Quiesce(ACTION, CLOSURE, null, 0L),
                        new Quiesce(ACTION, CLOSURE, ENROLLMENT, null),
                        new Quiesce(ACTION, CLOSURE, ENROLLMENT, -1L)))
            assertThatThrownBy(() -> PaymentAccountQuiescenceRules.admit(SELECTED, next, 0))
                    .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                PaymentAccountQuiescenceCanonical.canonical(
                                        new Selection(
                                                "tenant-one",
                                                ACCOUNT,
                                                ENROLLMENT,
                                                CONTRACT,
                                                "usd",
                                                2,
                                                "funds-policy-v1"),
                                        request()))
                .isInstanceOf(ResponseStatusException.class);
        bad(WIRE.replace(ACTION.toString(), "00000000-0000-0000-0000-000000000000"));
        bad(WIRE.replace(ACTION.toString(), "00000000-0000-0000-0000-00000000000A"));
        assertThatThrownBy(() -> PaymentAccountQuiescenceRules.decode(new byte[8193]))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(PaymentAccountQuiescenceCanonical.digest(SELECTED, request()))
                .isEqualTo("d637aa990f133cd14b621d2d023f7c59c5673f749038e95a14763a9465c440bc");
    }

    @Test
    void canonicalIdentityIncludesRealMoneyPrecisionAndPolicySelection() {
        assertThat(
                        PaymentAccountQuiescenceCanonical.digest(
                                new Selection(
                                        "tenant-one",
                                        ACCOUNT,
                                        ENROLLMENT,
                                        CONTRACT,
                                        "USD",
                                        3,
                                        "funds-policy-v1"),
                                request()))
                .isNotEqualTo(PaymentAccountQuiescenceCanonical.digest(SELECTED, request()));
        assertThat(
                        PaymentAccountQuiescenceCanonical.digest(
                                new Selection(
                                        "tenant-one",
                                        ACCOUNT,
                                        ENROLLMENT,
                                        CONTRACT,
                                        "USD",
                                        2,
                                        "funds-policy-v2"),
                                request()))
                .isNotEqualTo(PaymentAccountQuiescenceCanonical.digest(SELECTED, request()));
        assertThatThrownBy(
                        () ->
                                PaymentAccountQuiescenceCanonical.canonical(
                                        new Selection(
                                                "tenant-one",
                                                ACCOUNT,
                                                ENROLLMENT,
                                                CONTRACT,
                                                "USD",
                                                null,
                                                "funds-policy-v1"),
                                        request()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                PaymentAccountQuiescenceCanonical.canonical(
                                        new Selection(
                                                "tenant-one",
                                                ACCOUNT,
                                                ENROLLMENT,
                                                CONTRACT,
                                                "USD",
                                                2,
                                                null),
                                        request()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void releaseRequiresExactRetainedIdentityAndVersion() {
        String wire =
                "{\"actionId\":\""
                        + ACTION
                        + "\",\"closureId\":\""
                        + CLOSURE
                        + "\",\"expectedFenceVersion\":1}";
        var release =
                PaymentAccountQuiescenceRules.decodeRelease(wire.getBytes(StandardCharsets.UTF_8));
        assertThat(release).isEqualTo(new Release(ACTION, CLOSURE, 1L));
        assertThatThrownBy(
                        () ->
                                PaymentAccountQuiescenceRules.decodeRelease(
                                        wire.replace(":1}", ":1.0}")
                                                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                PaymentAccountQuiescenceRules.decodeRelease(
                                        wire.replace("}", ",\"clear\":true}")
                                                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void changedReleaseIntentCannotReplaySameAction() {
        var original = new Release(ACTION, CLOSURE, 1L);
        assertThat(PaymentAccountQuiescenceCanonical.releaseDigest(SELECTED, original))
                .isNotEqualTo(
                        PaymentAccountQuiescenceCanonical.releaseDigest(
                                SELECTED, new Release(ACTION, CLOSURE, 2L)))
                .isNotEqualTo(
                        PaymentAccountQuiescenceCanonical.releaseDigest(
                                SELECTED, new Release(ACTION, CONTRACT, 1L)));
    }

    @Test
    void retainedObservationPagesCannotUseUnboundedOrNegativeSelection() {
        assertThatCode(() -> PaymentAccountQuiescenceRules.page(0L, 100))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PaymentAccountQuiescenceRules.page(-1L, 100))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> PaymentAccountQuiescenceRules.page(0L, 101))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> PaymentAccountQuiescenceRules.page(0L, 0))
                .isInstanceOf(ResponseStatusException.class);
    }
}
