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

package io.fireflysoftware.core.banking.payments.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

class ManagedPaymentsContractTest {
    @Test
    void generatedManagedApiHasAllNativeOperationsWithoutInjectedAuthorityObjects()
            throws Exception {
        assertThatCode(
                        () ->
                                Class.forName(
                                        "io.fireflysoftware.core.banking.payments.sdk.api.ManagedPaymentsApi"))
                .doesNotThrowAnyException();
        Class<?> api =
                Class.forName(
                        "io.fireflysoftware.core.banking.payments.sdk.api.ManagedPaymentsApi");
        for (String operation :
                List.of(
                        "createManagedPayment",
                        "getManagedPayment",
                        "managedPaymentHistory",
                        "claimManagedPaymentEffect",
                        "recordManagedPaymentFailure",
                        "managedPaymentRecoveryQueue",
                        "acknowledgeManagedPaymentEffect",
                        "decideManagedPayment",
                        "recoverManagedPayment")) {
            var methods =
                    Arrays.stream(api.getDeclaredMethods())
                            .filter(method -> method.getName().equals(operation))
                            .toList();
            assertThat(methods).hasSize(1);
            String response =
                    switch (operation) {
                        case "claimManagedPaymentEffect" -> "EffectClaim";
                        case "managedPaymentHistory" -> "HistoryEvent";
                        case "managedPaymentRecoveryQueue" -> "RecoveryPage";
                        default -> "View";
                    };
            assertThat(methods.getFirst().getGenericReturnType().getTypeName())
                    .contains("io.fireflysoftware.core.banking.payments.sdk.model." + response);
            assertThat(Arrays.stream(methods.getFirst().getParameterTypes()).map(Class::getName))
                    .noneMatch(
                            name ->
                                    name.contains("Jwt")
                                            || name.contains("SecurityPrincipal")
                                            || name.contains("ExecutionContext"));
        }
    }

    @Test
    void generatedAmountsAndPinnedDeadlinesRetainTheirExactTypes() throws Exception {
        assertThatCode(
                        () ->
                                Class.forName(
                                        "io.fireflysoftware.core.banking.payments.sdk.model.Create"))
                .doesNotThrowAnyException();
        Class<?> create =
                Class.forName("io.fireflysoftware.core.banking.payments.sdk.model.Create");
        assertThat(create.getMethod("getAmount").getReturnType()).isEqualTo(BigDecimal.class);
        assertThat(create.getMethod("getCustomerNotAfterEpochMillis").getReturnType())
                .isEqualTo(Long.class);
        Class<?> view = Class.forName("io.fireflysoftware.core.banking.payments.sdk.model.View");
        assertThat(view.getMethod("getNotAfterEpochMillis").getReturnType()).isEqualTo(Long.class);
        assertThat(view.getMethod("getAmount").getReturnType()).isEqualTo(String.class);
        assertThat(view.getMethod("getLedgerState").getReturnType()).isEqualTo(String.class);
        assertThat(
                        Class.forName(
                                        "io.fireflysoftware.core.banking.payments.sdk.model.EffectClaim")
                                .getMethod("getFence")
                                .getReturnType())
                .isEqualTo(Long.class);
    }
}
