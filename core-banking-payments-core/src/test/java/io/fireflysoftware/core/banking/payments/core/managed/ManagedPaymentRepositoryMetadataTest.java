package io.fireflysoftware.core.banking.payments.core.managed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.fireflysoftware.core.banking.payments.models.configuration.ManagedPaymentPersistenceConfiguration;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentJournalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.r2dbc.core.*;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.UUID;

class ManagedPaymentRepositoryMetadataTest {
    private static final List<Class<?>> MANAGED_TYPES = List.of(
            ManagedPaymentOperationEntity.class, ManagedPaymentActionEntity.class,
            ManagedPaymentEffectEntity.class, ManagedPaymentMonetaryProofEntity.class,
            ManagedPaymentProjectionEntity.class, ManagedPaymentHistoryEntity.class,
            ManagedPaymentOutboxEntity.class);

    private static DefaultReactiveDataAccessStrategy strategy() {
        return new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE,
                ManagedPaymentPersistenceConfiguration.converters());
    }

    @Test
    void managedMappingsNameTheMigrationOwnedPublicSchema() {
        for (Class<?> type : MANAGED_TYPES) {
            assertThat(type.getAnnotation(Table.class).schema()).as(type.getSimpleName()).isEqualTo("public");
        }
    }

    @Test
    void mappedStatementsUseQualifiedPublicRelations() {
        var strategy = strategy();
        for (Class<?> type : MANAGED_TYPES) {
            var mapper = strategy.getStatementMapper().forType(type);
            var sql = mapper.getMappedObject(mapper.createSelect(strategy.getTableName(type))
                    .withProjection("tenant_id")).toQuery().replace("\"", "");
            assertThat(sql).as(type.getSimpleName()).contains("FROM public.managed_payment_");
        }
    }

    @Test
    void springDiscoversFunctionalImplFragmentAndDelegatesAssignedInsertWithoutDatabaseIo() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("owned-managed-repository-metadata-test");
            context.register(OwnedRepositoryConfiguration.class);
            context.refresh();
            var fragment = context.getBean("managedPaymentJournalOperationsImpl");
            assertThat(fragment.getClass().getPackageName())
                    .isEqualTo("io.fireflysoftware.core.banking.payments.models.repositories.managed.impl");
            var operations = context.getBean(R2dbcEntityOperations.class);
            var row = new ManagedPaymentOperationEntity(new ManagedPaymentIds.OperationId("bank", UUID.randomUUID()),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                    new PaymentJson("{}"), "RESERVING", 1L);
            when(operations.insert(row)).thenReturn(Mono.just(row));
            assertThat(context.getBean(ManagedPaymentJournalRepository.class).insertOperation(row).block()).isSameAs(row);
            verify(operations).insert(row);
            verifyNoInteractions(context.getBean(DatabaseClient.class));
        }
    }

    @Profile("owned-managed-repository-metadata-test")
    @Configuration(proxyBeanMethods = false)
    @EnableR2dbcRepositories(basePackageClasses = ManagedPaymentJournalRepository.class,
            entityOperationsRef = "ownedRepositoryOperations")
    static class OwnedRepositoryConfiguration {
        @Bean DatabaseClient ownedDatabaseClient() { return mock(DatabaseClient.class); }
        @Bean R2dbcEntityOperations ownedRepositoryOperations(DatabaseClient database) {
            var operations = mock(R2dbcEntityOperations.class);
            var strategy = strategy();
            when(operations.getDatabaseClient()).thenReturn(database);
            when(operations.getDataAccessStrategy()).thenReturn(strategy);
            when(operations.getConverter()).thenReturn(strategy.getConverter());
            return operations;
        }
    }
}
