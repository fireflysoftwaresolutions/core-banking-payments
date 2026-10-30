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
import java.util.ArrayList;
import java.util.function.BiFunction;

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
                        new io.fireflysoftware.core.banking.payments.models.repositories.managed.impl.ManagedPaymentJournalOperationsImpl(operations))))
                .isNotNull();
        org.mockito.Mockito.verifyNoInteractions(database);
    }

    @Test
    @SuppressWarnings("unchecked")
    void typedDatabaseClockExecutesTheCatalogQualifiedBuiltin() {
        var factory = org.mockito.Mockito.mock(io.r2dbc.spi.ConnectionFactory.class);
        var connection = org.mockito.Mockito.mock(io.r2dbc.spi.Connection.class);
        var statement = org.mockito.Mockito.mock(io.r2dbc.spi.Statement.class);
        var result = org.mockito.Mockito.mock(io.r2dbc.spi.Result.class);
        var row = org.mockito.Mockito.mock(io.r2dbc.spi.Row.class);
        var metadata = org.mockito.Mockito.mock(io.r2dbc.spi.RowMetadata.class);
        var queries = new ArrayList<String>();
        var observed = OffsetDateTime.parse("2026-10-30T12:00:00.123456Z");
        org.mockito.Mockito.when(factory.getMetadata()).thenReturn(() -> "PostgreSQL");
        org.mockito.Mockito.doReturn(reactor.core.publisher.Mono.just(connection)).when(factory).create();
        org.mockito.Mockito.when(connection.close()).thenReturn(reactor.core.publisher.Mono.empty());
        org.mockito.Mockito.when(connection.createStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            queries.add(call.getArgument(0));return statement;
        });
        org.mockito.Mockito.doReturn(reactor.core.publisher.Flux.just(result)).when(statement).execute();
        org.mockito.Mockito.when(row.get(0, OffsetDateTime.class)).thenReturn(observed);
        org.mockito.Mockito.when(result.map(org.mockito.ArgumentMatchers.any(BiFunction.class))).thenAnswer(call -> {
            BiFunction<io.r2dbc.spi.Row, io.r2dbc.spi.RowMetadata, Object> mapper = call.getArgument(0);
            return reactor.core.publisher.Flux.just(mapper.apply(row, metadata));
        });
        var operations = new org.springframework.data.r2dbc.core.R2dbcEntityTemplate(
                org.springframework.r2dbc.core.DatabaseClient.create(factory), strategy);
        var repositories = new org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory(operations);
        var clock = repositories.getRepository(
                io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentNativeRepository.class);
        assertThat(clock.databaseTime().block()).isEqualTo(observed);
        assertThat(queries).containsExactly("SELECT pg_catalog.clock_timestamp()");
        org.mockito.Mockito.verify(connection).close();
    }
}
