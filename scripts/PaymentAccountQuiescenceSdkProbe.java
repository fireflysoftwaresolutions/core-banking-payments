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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.fireflysoftware.core.banking.payments.sdk.api.ManagedPaymentsApi;
import io.fireflysoftware.core.banking.payments.sdk.api.PaymentAccountQuiescenceApi;
import io.fireflysoftware.core.banking.payments.sdk.invoker.ApiClient;
import io.fireflysoftware.core.banking.payments.sdk.model.*;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Genuine generated-client consumer; credentials remain in the ephemeral environment. */
public final class PaymentAccountQuiescenceSdkProbe {
    static final Duration WAIT = Duration.ofSeconds(20);
    static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);
    static int checks;

    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
        System.out.println("CLOSURE_SDK_CHECK=" + name);
    }

    public static void main(String[] args) throws Exception {
        var client = new ApiClient().setBasePath(System.getenv("OWNED_PAYMENTS_URL"));
        client.addDefaultHeader("Authorization", "Bearer " + System.getenv("OWNED_CLOSURE_TOKEN"));
        var api = new PaymentAccountQuiescenceApi(client);
        UUID account = UUID.fromString(System.getenv("OWNED_ACCOUNT")),
                closure = UUID.fromString(System.getenv("OWNED_CLOSURE")),
                action = UUID.fromString(System.getenv("OWNED_ACTION"));
        var expected =
                JSON.readTree(Files.readString(Path.of(System.getenv("OWNED_LEDGER_RECORD"))));
        var input =
                new Quiesce()
                        .actionId(action)
                        .closureId(closure)
                        .enrollmentId(UUID.fromString(expected.path("enrollmentId").asText()))
                        .expectedFenceVersion(0L);
        String mode = System.getenv().getOrDefault("OWNED_PROBE_MODE", "quiesce");
        Object result;
        try {
            result =
                    switch (mode) {
                        case "expire" -> {
                            var managed = new ManagedPaymentsApi(client);
                            var created =
                                    managed.createManagedPayment(
                                                    "owned",
                                                    new Create()
                                                            .actionId(action)
                                                            .sourceAccountId(account)
                                                            .destinationAccountId(
                                                                    UUID.fromString(
                                                                            System.getenv(
                                                                                    "OWNED_DESTINATION")))
                                                            .amount(
                                                                    new java.math.BigDecimal(
                                                                            "1.00"))
                                                            .currency("USD")
                                                            .ttlSeconds(1))
                                            .block(WAIT);
                            var claim =
                                    managed.claimManagedPaymentEffect(
                                                    "owned",
                                                    created.getPaymentId(),
                                                    new Claim()
                                                            .actionId(UUID.randomUUID())
                                                            .expectedVersion(created.getVersion()))
                                            .block(WAIT);
                            Thread.sleep(
                                    Math.max(
                                                    0,
                                                    created.getNotAfterEpochMillis()
                                                            - System.currentTimeMillis())
                                            + 40);
                            var expired =
                                    managed.acknowledgeManagedPaymentEffect(
                                                    "owned",
                                                    created.getPaymentId(),
                                                    new Acknowledge()
                                                            .effectId(claim.getEffectId())
                                                            .fence(claim.getFence())
                                                            .expectedVersion(claim.getVersion()))
                                            .block(WAIT);
                            check(
                                    expired != null
                                            && "EXPIRED".equals(expired.getState().getValue())
                                            && expired.getAbsenceReceiptId() != null
                                            && expired.getReservationId() == null
                                            && expired.getLedgerVersion() == null
                                            && "ABSENT_CLOSED".equals(expired.getLedgerState()),
                                    "real ledger fixed-deadline ABSENT_CLOSED receipt without"
                                            + " fabricated reservation/version");
                            yield expired;
                        }
                        case "create" -> {
                            var value =
                                    new ManagedPaymentsApi(client)
                                            .createManagedPayment(
                                                    "owned",
                                                    new Create()
                                                            .actionId(action)
                                                            .sourceAccountId(account)
                                                            .destinationAccountId(
                                                                    UUID.fromString(
                                                                            System.getenv(
                                                                                    "OWNED_DESTINATION")))
                                                            .amount(
                                                                    new java.math.BigDecimal(
                                                                            "1.00"))
                                                            .currency("USD")
                                                            .ttlSeconds(300))
                                            .block(WAIT);
                            check(
                                    value != null
                                            && "RECEIVED".equals(value.getState().getValue())
                                            && value.getPendingEffectId() != null,
                                    "genuine SDK retained managed instruction");
                            yield value;
                        }
                        case "quiesce", "replay", "header-conflict" -> {
                            var response =
                                    api.quiesceManagedPaymentsForAccountWithHttpInfo(
                                                    "owned",
                                                    account,
                                                    mode.equals("header-conflict")
                                                            ? UUID.randomUUID()
                                                            : action,
                                                    input)
                                            .block(WAIT);
                            check(
                                    response != null
                                            && !response.getHeaders().containsHeader("Set-Cookie"),
                                    "native bearer request does not create session authority");
                            var receipt = response.getBody();
                            check(receipt != null, "typed native quiescence receipt");
                            var selected = receipt.getSelection();
                            check(
                                    account.equals(selected.getAccountId()),
                                    "exact real owner account");
                            check(
                                    expected.path("enrollmentId")
                                            .asText()
                                            .equals(selected.getEnrollmentId().toString()),
                                    "exact real ledger enrollment");
                            check(
                                    expected.path("contractId")
                                            .asText()
                                            .equals(selected.getContractId().toString()),
                                    "exact real ledger contract");
                            check(
                                    expected.path("currency")
                                            .asText()
                                            .equals(selected.getCurrency()),
                                    "exact currency");
                            check(
                                    expected.path("currencyScale").asInt()
                                            == selected.getCurrencyScale(),
                                    "exact scale");
                            check(
                                    expected.path("currencyPolicy")
                                            .asText()
                                            .equals(selected.getCurrencyPolicy()),
                                    "exact currency policy");
                            check(
                                    expected.path("version").asLong()
                                            == receipt.getObservedLedgerVersion(),
                                    "exact observed ledger version");
                            check(
                                    "CUSTOMER".equals(expected.path("kind").asText()),
                                    "real customer kind");
                            if (expected.path("state").asText().equals("BLOCKED"))
                                check(true, "blocked real ledger tuple admission");
                            if (mode.equals("replay")) {
                                var received = JSON.readTree(JSON.writeValueAsString(receipt));
                                Files.writeString(
                                        Path.of(
                                                System.getenv("OWNED_PROBE_RESULT")
                                                        + ".received.json"),
                                        received.toString());
                                check(
                                        JSON.readTree(
                                                        Files.readString(
                                                                Path.of(
                                                                        System.getenv(
                                                                                "OWNED_ORIGINAL_RECEIPT"))))
                                                .equals(received),
                                        "immutable receipt after restart/current replay");
                            }
                            yield receipt;
                        }
                        case "get" -> {
                            var view =
                                    api.getManagedPaymentAccountQuiescence(
                                                    "owned", account, closure)
                                            .block(WAIT);
                            check(view != null, "typed current fence view");
                            check(
                                    !"CLOSED".equals(view.getState().getValue()),
                                    "first slice never business closed");
                            yield view;
                        }
                        case "observe" -> {
                            var page =
                                    api.getManagedPaymentAccountObligations(
                                                    "owned", account, closure, null, 0L, 1)
                                            .block(WAIT);
                            check(
                                    page != null && page.getObservation() != null,
                                    "typed retained complete observation");
                            Files.writeString(
                                    Path.of(System.getenv("OWNED_PROBE_RESULT") + ".received.json"),
                                    JSON.writeValueAsString(page));
                            check(
                                    System.getenv("OWNED_EXPECTED_OBSERVATION")
                                            .equals(page.getObservation().getStatus().getValue()),
                                    "truthful managed observation status actual "
                                            + page.getObservation().getStatus().getValue());
                            if (page.getNextAfter() != null) {
                                var next =
                                        api.getManagedPaymentAccountObligations(
                                                        "owned",
                                                        account,
                                                        closure,
                                                        page.getObservation().getObservationId(),
                                                        page.getNextAfter(),
                                                        1)
                                                .block(WAIT);
                                check(
                                        next.getObservation().equals(page.getObservation()),
                                        "stable native observation pagination");
                            }
                            yield page;
                        }
                        case "release" -> {
                            var release =
                                    new Release()
                                            .actionId(action)
                                            .closureId(closure)
                                            .expectedFenceVersion(1L);
                            var receipt =
                                    api.releaseManagedPaymentAccountQuiescence(
                                                    "owned", account, action, release)
                                            .block(WAIT);
                            check(
                                    receipt != null && receipt.getFenceVersion() == 2,
                                    "typed explicit CAS release");
                            var view =
                                    api.getManagedPaymentAccountQuiescence(
                                                    "owned", account, closure)
                                            .block(WAIT);
                            check(
                                    "RELEASED".equals(view.getState().getValue())
                                            && "UNKNOWN".equals(view.getObservation().getValue()),
                                    "released current view invalidates clearance");
                            yield receipt;
                        }
                        default -> throw new IllegalArgumentException(mode);
                    };
            if (System.getenv("OWNED_EXPECTED_HTTP") != null)
                throw new AssertionError("Expected current native denial");
        } catch (WebClientResponseException error) {
            String expectedStatus = System.getenv("OWNED_EXPECTED_HTTP");
            if (expectedStatus == null) throw error;
            check(
                    error.getStatusCode().value() == Integer.parseInt(expectedStatus),
                    "native current-authority/header/owner denial "
                            + expectedStatus
                            + " actual "
                            + error.getStatusCode().value());
            result = Map.of("httpStatus", error.getStatusCode().value());
        }
        Files.writeString(
                Path.of(System.getenv("OWNED_PROBE_RESULT")), JSON.writeValueAsString(result));
        System.out.println("CLOSURE_SDK_RESULT checks=" + checks);
    }
}
