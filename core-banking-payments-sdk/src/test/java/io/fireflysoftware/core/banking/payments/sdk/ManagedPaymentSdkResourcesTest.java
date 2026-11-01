package io.fireflysoftware.core.banking.payments.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ManagedPaymentSdkResourcesTest {
    @Test
    void packagedCompatibilityAliasMatchesActualProducerSpec() throws Exception {
        try (var root = getClass().getResourceAsStream("/openapi.yml");
                var alias = getClass().getResourceAsStream("/api-spec/openapi.yml")) {
            assertThat(root).isNotNull();
            assertThat(alias).isNotNull();
            assertThat(alias.readAllBytes()).isEqualTo(root.readAllBytes());
        }
    }
}
