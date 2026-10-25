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

import io.fireflysoftware.core.banking.payments.sdk.api.ManagedPaymentsApi;
import io.fireflysoftware.core.banking.payments.sdk.invoker.ApiClient;
import io.fireflysoftware.core.banking.payments.sdk.model.*;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/** Private synthetic consumer; no bearer token is written to its result or command line. */
public class ManagedPaymentsSdkProbe {
    private static int checks;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        checks++;
        System.out.println("OWNED_PAYMENT_SDK_CHECK=" + description);
    }

    private static ManagedPaymentsApi client(String token) {
        ApiClient client = new ApiClient().setBasePath(System.getenv("OWNED_PAYMENTS_URL"));
        client.addDefaultHeader("Authorization", "Bearer " + token);
        return new ManagedPaymentsApi(client);
    }

    private static void denied(Runnable request, int status) {
        try {
            request.run();
            throw new AssertionError("Expected HTTP " + status);
        } catch (WebClientResponseException error) {
            check(
                    error.getStatusCode().value() == status,
                    "Exact current-authority/conflict status");
        }
    }

    public static void main(String[] arguments) throws Exception {
        ManagedPaymentsApi api = client(System.getenv("OWNED_PAYMENTS_TOKEN"));
        Create input =
                new Create()
                        .actionId(UUID.randomUUID())
                        .sourceAccountId(UUID.fromString(System.getenv("OWNED_SOURCE_ACCOUNT")))
                        .destinationAccountId(
                                UUID.fromString(System.getenv("OWNED_DESTINATION_ACCOUNT")))
                        .amount(new BigDecimal("1.00"))
                        .currency("USD")
                        .ttlSeconds(1);
        var created = api.createManagedPaymentWithHttpInfo("owned", input).block(TIMEOUT);
        check(created != null && created.getStatusCode().value() == 201, "Native SDK creation201");
        View initial = created.getBody();
        check(
                initial != null
                        && initial.getAmount().equals("1.00")
                        && initial.getNotAfterEpochMillis() > 0
                        && initial.getPendingEffectId() != null,
                "Typed exact initial intent");
        var replay = api.createManagedPaymentWithHttpInfo("owned", input).block(TIMEOUT);
        check(
                replay != null
                        && replay.getStatusCode().value() == 200
                        && replay.getBody().getPaymentId().equals(initial.getPaymentId()),
                "SDK retained creation200");
        denied(
                () ->
                        client(System.getenv("OWNED_REVOKED_TOKEN"))
                                .createManagedPayment("owned", input)
                                .block(TIMEOUT),
                403);
        input.amount(new BigDecimal("2.00"));
        denied(() -> api.createManagedPayment("owned", input).block(TIMEOUT), 409);
        EffectClaim claim =
                api.claimManagedPaymentEffect(
                                "owned",
                                initial.getPaymentId(),
                                new Claim()
                                        .actionId(UUID.randomUUID())
                                        .expectedVersion(initial.getVersion()))
                        .block(TIMEOUT);
        check(
                claim != null && claim.getFence() == 1L && claim.getPayment() != null,
                "Typed stable effect claim");
        Thread.sleep(
                Math.max(0, initial.getNotAfterEpochMillis() - System.currentTimeMillis()) + 30);
        View expired =
                api.acknowledgeManagedPaymentEffect(
                                "owned",
                                initial.getPaymentId(),
                                new Acknowledge()
                                        .effectId(claim.getEffectId())
                                        .fence(claim.getFence())
                                        .expectedVersion(claim.getVersion()))
                        .block(TIMEOUT);
        check(
                expired != null
                        && expired.getState() == View.StateEnum.EXPIRED
                        && expired.getAbsenceReceiptId() != null
                        && expired.getPostingId() == null
                        && expired.getReservationId() == null,
                "Actual ledger closed-window SDK receipt");
        View retained = api.getManagedPayment("owned", initial.getPaymentId(), null).block(TIMEOUT);
        check(
                retained != null
                        && retained.getAbsenceReceiptId().equals(expired.getAbsenceReceiptId()),
                "Typed read preserves absence receipt");
        var history =
                api.managedPaymentHistory("owned", initial.getPaymentId(), -1L, 25, null)
                        .collectList()
                        .block(TIMEOUT);
        check(
                history != null
                        && history.stream()
                                .anyMatch(e -> e.getType().equals("LEDGER_ABSENT_CLOSED")),
                "Typed bounded native history");
        var queue = api.managedPaymentRecoveryQueue("owned", null, 25, null).block(TIMEOUT);
        check(queue != null && queue.getItems() != null, "Typed bounded recovery queue");
        denied(
                () ->
                        client(System.getenv("OWNED_REVOKED_TOKEN"))
                                .getManagedPayment("owned", initial.getPaymentId(), null)
                                .block(TIMEOUT),
                404);
        System.out.println("OWNED_PAYMENT_SDK_ASSERTIONS=" + checks);
    }
}
