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

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class PaymentAccountQuiescenceContractTest {
    @Test
    void genuineGeneratedOwnerApiHasAllFourTypedOperationsAndNoCallerAuthorityObjects()
            throws Exception {
        Class<?> api =
                Class.forName(
                        "io.fireflysoftware.core.banking.payments.sdk.api.PaymentAccountQuiescenceApi");
        var responses =
                Map.of(
                        "quiesceManagedPaymentsForAccount",
                        "QuiescenceReceipt",
                        "getManagedPaymentAccountQuiescence",
                        "PaymentAccountQuiescenceView",
                        "getManagedPaymentAccountObligations",
                        "ObligationPage",
                        "releaseManagedPaymentAccountQuiescence",
                        "ReleaseReceipt");
        for (var entry : responses.entrySet()) {
            var methods =
                    Arrays.stream(api.getDeclaredMethods())
                            .filter(m -> m.getName().equals(entry.getKey()))
                            .toList();
            assertThat(methods).hasSize(1);
            var method = methods.getFirst();
            assertThat(method.getGenericReturnType().getTypeName())
                    .contains(
                            "io.fireflysoftware.core.banking.payments.sdk.model."
                                    + entry.getValue());
            assertThat(Arrays.stream(method.getParameterTypes()).map(Class::getName))
                    .noneMatch(
                            n ->
                                    n.contains("Jwt")
                                            || n.contains("SecurityPrincipal")
                                            || n.contains("ExecutionContext"));
            if (entry.getKey().equals("quiesceManagedPaymentsForAccount")
                    || entry.getKey().equals("releaseManagedPaymentAccountQuiescence"))
                assertThat(
                                Arrays.stream(method.getParameterTypes())
                                        .filter(t -> t.equals(UUID.class))
                                        .count())
                        .isEqualTo(2);
        }
    }

    @Test
    void originalGeneratedManagedApiRemainsPresentWithTypedActionIdentity() throws Exception {
        var api =
                Class.forName(
                        "io.fireflysoftware.core.banking.payments.sdk.api.ManagedPaymentsApi");
        assertThat(Arrays.stream(api.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .contains(
                        "createManagedPayment",
                        "getManagedPayment",
                        "managedPaymentRecoveryQueue",
                        "acknowledgeManagedPaymentEffect");
        var create = Class.forName("io.fireflysoftware.core.banking.payments.sdk.model.Create");
        assertThat(create.getMethod("getActionId").getReturnType()).isEqualTo(UUID.class);
    }

    @Test
    void generatedCommandSchemasDeclareTheStrictRequiredWireFields() throws Exception {
        var source =
                java.nio.file.Path.of(
                        System.getProperty(
                                "closure.producer.openapi", "src/main/resources/openapi.yml"));
        var root =
                new com.fasterxml.jackson.databind.ObjectMapper(
                                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                        .readTree(java.nio.file.Files.readString(source));
        for (var entry :
                Map.of(
                                "Quiesce",
                                Set.of(
                                        "actionId",
                                        "closureId",
                                        "enrollmentId",
                                        "expectedFenceVersion"),
                                "Release",
                                Set.of("actionId", "closureId", "expectedFenceVersion"))
                        .entrySet()) {
            var schema = root.path("components").path("schemas").path(entry.getKey());
            var required = new HashSet<String>();
            schema.path("required").forEach(value -> required.add(value.asText()));
            assertThat(required).containsExactlyInAnyOrderElementsOf(entry.getValue());
            assertThat(
                            schema.path("additionalProperties").isBoolean()
                                    && !schema.path("additionalProperties").asBoolean())
                    .isTrue();
            assertThat(schema.path("properties").path("actionId").path("format").asText())
                    .isEqualTo("uuid");
            assertThat(
                            schema.path("properties")
                                    .path("expectedFenceVersion")
                                    .path("format")
                                    .asText())
                    .isEqualTo("int64");
        }
    }
}
