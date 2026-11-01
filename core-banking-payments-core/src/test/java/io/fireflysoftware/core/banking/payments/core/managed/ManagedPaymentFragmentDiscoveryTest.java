package io.fireflysoftware.core.banking.payments.core.managed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.fireflysoftware.core.banking.payments.models.configuration.ManagedPaymentPersistenceConfiguration;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.r2dbc.core.*;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

class ManagedPaymentFragmentDiscoveryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableR2dbcRepositories(basePackageClasses = ManagedPaymentJournalRepository.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*ManagedPayment(?:Action|Effect|History|Operation|Native)Repository"))
    static class Repositories {
        @Bean
        ConnectionFactory connectionFactory() {
            var factory = mock(ConnectionFactory.class);
            when(factory.getMetadata()).thenReturn(() -> "PostgreSQL");
            return factory;
        }

        @Bean
        R2dbcEntityTemplate r2dbcEntityTemplate(ConnectionFactory factory) {
            return spy(new R2dbcEntityTemplate(DatabaseClient.create(factory),
                    new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE,
                            ManagedPaymentPersistenceConfiguration.converters())));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void discoveredImplSubpackageFragmentKeepsItsBeanNameAndHandlesRepositoryRescheduling() throws Exception {
        Class<?> implementation = null;
        try {
            implementation = Class.forName("io.fireflysoftware.core.banking.payments.models.repositories.managed.impl.ManagedPaymentJournalOperationsImpl");
        } catch (ClassNotFoundException missing) { }
        assertThat(implementation).as("Implementation-private fragment package").isNotNull();
        try (var context = new AnnotationConfigApplicationContext(Repositories.class)) {
            assertThat(context.getBean("managedPaymentJournalOperationsImpl")).isInstanceOf(implementation);
            var template = context.getBean(R2dbcEntityTemplate.class);
            doReturn(Mono.just(1L)).when(template).update(any(Query.class), any(Update.class), eq(ManagedPaymentEffectEntity.class));
            var method = Arrays.stream(ManagedPaymentJournalOperations.class.getMethods())
                    .filter(candidate -> candidate.getName().equals("rescheduleEffect")).findFirst().orElseThrow();
            var expected = new ManagedPaymentJournalOperations.EffectVersion(
                    new ManagedPaymentIds.EffectId("bank", UUID.randomUUID()), 7L, 3);
            var result = (Mono<Long>) method.invoke(context.getBean(ManagedPaymentJournalRepository.class),
                    expected, "PENDING", Instant.parse("2026-10-30T12:00:00.123456Z"));
            assertThat(result.block()).isEqualTo(1L);
            verify(template).update(any(Query.class), any(Update.class), eq(ManagedPaymentEffectEntity.class));
            verify(context.getBean(ConnectionFactory.class), never()).create();
        }
    }
}
