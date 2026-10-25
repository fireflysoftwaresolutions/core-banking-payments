package io.fireflysoftware.core.banking.payments.core.managed;

import static org.assertj.core.api.Assertions.*;
import io.fireflysoftware.core.banking.payments.models.configuration.ManagedPaymentPersistenceConfiguration;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import java.time.OffsetDateTime;
import java.util.UUID;

class ManagedPaymentPersistenceMappingTest {
    private final DefaultReactiveDataAccessStrategy strategy = new DefaultReactiveDataAccessStrategy(
            PostgresDialect.INSTANCE, ManagedPaymentPersistenceConfiguration.converters());

    @Test
    void compositeActionIdentityMapsToFlatTenantAndActionColumns() {
        var id = new ManagedPaymentIds.ActionId("bank", UUID.randomUUID());
        var entity = new ManagedPaymentActionEntity(id, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "CREATE", "a".repeat(64), "actor", null);
        var row = strategy.getOutboundRow(entity);
        assertThat(row.keySet().stream().map(k -> k.getReference()))
                .contains("tenant_id", "action_id").doesNotContain("id", "id_tenant_id", "id_action_id");
        assertThat(row.entrySet().stream().filter(e -> e.getKey().getReference().equals("tenant_id"))
                .findFirst().orElseThrow().getValue().getValue()).isEqualTo("bank");
    }

    @Test
    void jsonConversionPreservesMoneyNullLargeIntegersAndTimestampBytesWithoutGlobalTextConversion() {
        String value = "{\"amount\":12.3400,\"version\":9223372036854775807,\"nullable\":null,\"observedAt\":\"2026-10-24T01:02:03.123Z\"}";
        var conversion = strategy.getConverter().getConversionService();
        Json encoded = conversion.convert(new PaymentJson(value), Json.class);
        assertThat(encoded.asString()).isEqualTo(value);
        assertThat(conversion.convert(encoded, PaymentJson.class)).isEqualTo(new PaymentJson(value));
        var baseline = new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE);
        assertThat(conversion.canConvert(String.class, Json.class))
                .isEqualTo(baseline.getConverter().getConversionService().canConvert(String.class, Json.class));
        var action = new ManagedPaymentActionEntity(new ManagedPaymentIds.ActionId("bank", UUID.randomUUID()),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CREATE", "a".repeat(64), "actor", null);
        var row = strategy.getOutboundRow(action);
        assertThat(row.entrySet().stream().filter(e -> e.getKey().getReference().equals("actor_subject"))
                .findFirst().orElseThrow().getValue().getValue()).isEqualTo("actor");
    }

    @Test
    void assignedEffectIdsDoNotWriteTheDatabaseOwnedRetryClock() {
        var row = strategy.getOutboundRow(new ManagedPaymentEffectEntity(
                new ManagedPaymentIds.EffectId("bank", UUID.randomUUID()), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RESERVE", "PENDING", 0L, 0,
                null, null, OffsetDateTime.parse("2026-10-24T01:00:00Z")));
        assertThat(row.keySet().stream().map(k -> k.getReference()))
                .contains("tenant_id", "effect_id", "fence", "attempts").doesNotContain("next_attempt_at");
    }
    @Test
    void actualRepositoryFactoryAcceptsEveryCompositeDerivedPathWithoutDatabaseIo() {
        var operations = org.mockito.Mockito.mock(org.springframework.data.r2dbc.core.R2dbcEntityOperations.class);
        var database = org.mockito.Mockito.mock(org.springframework.r2dbc.core.DatabaseClient.class);
        org.mockito.Mockito.when(operations.getDatabaseClient()).thenReturn(database);
        org.mockito.Mockito.when(operations.getDataAccessStrategy()).thenReturn(strategy);
        org.mockito.Mockito.when(operations.getConverter()).thenReturn(strategy.getConverter());
        var factory = new org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory(operations);
        for (Class<?> type : java.util.List.of(
                io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentOperationRepository.class,
                io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentActionRepository.class,
                io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentEffectRepository.class,
                io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentHistoryRepository.class,
                io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentNativeRepository.class,
                io.fireflysoftware.core.banking.payments.models.repositories.closure.PaymentAccountActionRepository.class,
                io.fireflysoftware.core.banking.payments.models.repositories.closure.PaymentAccountFenceRepository.class,
                io.fireflysoftware.core.banking.payments.models.repositories.closure.PaymentAccountObservationRepository.class,
                io.fireflysoftware.core.banking.payments.models.repositories.closure.PaymentAccountObservationMemberRepository.class,
                io.fireflysoftware.core.banking.payments.models.repositories.closure.PaymentAccountNativeRepository.class)) {
            assertThat(factory.getRepository(type)).isNotNull();
        }
        assertThat(factory.getRepository(
                io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentJournalRepository.class,
                org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments.just(
                        new io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentJournalOperationsImpl(operations))))
                .isNotNull();
        org.mockito.Mockito.verifyNoInteractions(database);
    }
}
