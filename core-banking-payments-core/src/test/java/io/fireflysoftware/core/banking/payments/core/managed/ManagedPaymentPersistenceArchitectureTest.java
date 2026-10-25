package io.fireflysoftware.core.banking.payments.core.managed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ManagedPaymentPersistenceArchitectureTest {
    @Test
    void ownerStoresAndContextUseSpringDataBoundariesWithoutRawSql() throws Exception {
        Path root = Path.of("src/main/java/io/fireflysoftware/core/banking/payments/core");
        for (String path : List.of("managed/impl/ManagedPaymentStore.java",
                "managed/impl/ManagedPaymentDatabaseContext.java",
                "services/closure/impl/PaymentAccountQuiescenceStore.java",
                "services/closure/impl/PaymentAccountQuiescenceDatabaseContext.java")) {
            assertThat(Files.readString(root.resolve(path))).as(path)
                    .doesNotContain("org.springframework.r2dbc.core.DatabaseClient", ".sql(");
        }
    }

    @Test
    void mappedAuthorityRepositoriesExposeNoGenericMutationOrUnboundedRead() {
        for (String name : List.of("managed.ManagedPaymentOperationRepository",
                "managed.ManagedPaymentActionRepository", "managed.ManagedPaymentEffectRepository",
                "managed.ManagedPaymentHistoryRepository", "closure.PaymentAccountActionRepository",
                "closure.PaymentAccountFenceRepository", "closure.PaymentAccountObservationRepository",
                "closure.PaymentAccountObservationMemberRepository")) {
            Class<?> type = null;
            try { type = Class.forName("io.fireflysoftware.core.banking.payments.models.repositories." + name); }
            catch (ClassNotFoundException missing) { /* The missing repository is the maintained RED. */ }
            assertThat(type).as(name).isNotNull();
            assertThat(java.util.Arrays.stream(type.getMethods()).map(java.lang.reflect.Method::getName))
                    .noneMatch(method -> method.equals("save") || method.startsWith("delete") || method.equals("findAll"));
        }
    }
}
