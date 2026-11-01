package io.fireflysoftware.core.banking.payments.core.managed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.fireflysoftware.core.banking.payments.models.configuration.ManagedPaymentPersistenceConfiguration;
import io.fireflysoftware.core.banking.payments.models.entities.managed.PaymentJson;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentJournalOperations;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.impl.ManagedPaymentJournalOperationsImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;
import java.util.*;

class ManagedPaymentMonetaryProofMappingTest {
    private static final String ENTITY = "io.fireflysoftware.core.banking.payments.models.entities.managed.ManagedPaymentMonetaryProofEntity";
    private static final String REPOSITORY = "io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentMonetaryProofRepository";
    private final DefaultReactiveDataAccessStrategy strategy = new DefaultReactiveDataAccessStrategy(
            PostgresDialect.INSTANCE, ManagedPaymentPersistenceConfiguration.converters());

    private static Class<?> required(String name) throws Exception {
        assertThatCode(() -> Class.forName(name)).doesNotThrowAnyException();
        return Class.forName(name);
    }

    private static Object proof() throws Exception {
        var entity = required(ENTITY);
        var id = required("io.fireflysoftware.core.banking.payments.models.entities.managed.ManagedPaymentIds$ProofId")
                .getConstructor(String.class, UUID.class).newInstance("owned", new UUID(0, 7));
        var types = Arrays.stream(entity.getRecordComponents()).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        return entity.getConstructor(types).newInstance(id, new UUID(0, 1), new UUID(0, 2), new UUID(0, 3),
                new UUID(0, 9), "COMMIT", new UUID(0, 6), 100L, 2L, 3L,
                OffsetDateTime.parse("2026-10-24T01:02:03.123Z"), "observing-worker",
                new PaymentJson("{\"canonical\":{\"digest\":null},\"value\":{\"amount\":1.2300}}"),
                OffsetDateTime.parse("2026-10-24T02:00:00Z"));
    }

    @Test
    void assignedProofMapsExactTenantEffectAndPreservesOwnerTimestamp() throws Exception {
        var row = strategy.getOutboundRow(proof());
        var names = row.keySet().stream().map(k -> k.getReference()).toList();
        assertThat(names).contains("tenant_id", "effect_id", "receipt_envelope", "retained_version",
                "observed_reservation_version", "observed_at").doesNotContain("id", "id_tenant_id", "retained_at");
        assertThat(row.entrySet().stream().filter(e -> e.getKey().getReference().equals("observed_at"))
                .findFirst().orElseThrow().getValue().getValue()).isEqualTo(OffsetDateTime.parse("2026-10-24T01:02:03.123Z"));
    }

    @Test
    void proofRepositoryDeclaresOnlyExactScopedReads() throws Exception {
        var type = required(REPOSITORY);
        assertThat(Arrays.stream(type.getMethods()).map(java.lang.reflect.Method::getName).toList())
                .containsExactlyInAnyOrder("findById", "findByIdTenantIdAndPaymentIdAndKind");
    }

    @Test
    void actualSpringDataFactoryAcceptsProofCompositePropertyPathsWithoutDatabase() throws Exception {
        var operations = mock(R2dbcEntityOperations.class);
        var database = mock(org.springframework.r2dbc.core.DatabaseClient.class);
        when(operations.getDatabaseClient()).thenReturn(database);
        when(operations.getDataAccessStrategy()).thenReturn(strategy);
        when(operations.getConverter()).thenReturn(strategy.getConverter());
        var factory = new org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory(operations);
        assertThat(factory.getRepository(required(REPOSITORY))).isNotNull();
        verifyNoInteractions(database);
    }

    @Test
    void assignedProofUsesExistingMappedInsertBoundary() throws Exception {
        var row = proof();
        var operations = mock(R2dbcEntityOperations.class);
        when(operations.insert(row)).thenReturn(Mono.just(row));
        assertThat(Arrays.stream(ManagedPaymentJournalOperations.class.getMethods())
                .anyMatch(m -> m.getName().equals("insertMonetaryProof"))).isTrue();
        var journal = new ManagedPaymentJournalOperationsImpl(operations);
        var method = ManagedPaymentJournalOperations.class.getMethod("insertMonetaryProof", row.getClass());
        assertThat(((Mono<?>) method.invoke(journal, row)).block()).isSameAs(row);
        verify(operations).insert(row);
        verifyNoMoreInteractions(operations);
    }
}
