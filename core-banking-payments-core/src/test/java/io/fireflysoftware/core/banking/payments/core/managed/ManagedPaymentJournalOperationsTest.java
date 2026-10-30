package io.fireflysoftware.core.banking.payments.core.managed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.fireflysoftware.core.banking.payments.models.configuration.ManagedPaymentPersistenceConfiguration;
import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.*;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.relational.core.query.*;
import org.springframework.r2dbc.core.binding.BindTarget;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.*;

class ManagedPaymentJournalOperationsTest {
    private final R2dbcEntityOperations operations = mock(R2dbcEntityOperations.class);
    private final DefaultReactiveDataAccessStrategy strategy = new DefaultReactiveDataAccessStrategy(
            PostgresDialect.INSTANCE, ManagedPaymentPersistenceConfiguration.converters());
    private final List<Object> binds = new ArrayList<>();
    private String sql;

    private ManagedPaymentJournalOperations journal() throws Exception {
        Class<?> type = null;
        try { type = Class.forName("io.fireflysoftware.core.banking.payments.models.repositories.managed.impl.ManagedPaymentJournalOperationsImpl"); }
        catch (ClassNotFoundException missing) { }
        assertThat(type).as("Mapped journal fragment implementation").isNotNull();
        return (ManagedPaymentJournalOperations) type.getConstructor(R2dbcEntityOperations.class).newInstance(operations);
    }

    private void capture(long affected) {
        when(operations.update(any(Query.class), any(Update.class), any(Class.class))).thenAnswer(call -> {
            Query query = call.getArgument(0); Update update = call.getArgument(1); Class<?> type = call.getArgument(2);
            var mapper = strategy.getStatementMapper().forType(type);
            var statement = mapper.getMappedObject(mapper.createUpdate(strategy.getTableName(type), update)
                    .withCriteria(query.getCriteria().orElseThrow()));
            sql = statement.toQuery().replace("\"", "");
            statement.bindTo(new BindTarget() {
                public void bind(String id, Object value) { binds.add(value); }
                public void bind(int id, Object value) { binds.add(value); }
                public void bindNull(String id, Class<?> type) { binds.add(null); }
                public void bindNull(int id, Class<?> type) { binds.add(null); }
            });
            return Mono.just(affected);
        });
    }

    private ManagedPaymentJournalOperations.EffectVersion effect(long fence, int attempts) {
        return new ManagedPaymentJournalOperations.EffectVersion(
                new ManagedPaymentIds.EffectId("bank", UUID.randomUUID()), fence, attempts);
    }

    @Test
    void operationCasBindsTenantPaymentPriorVersionAndAlignedPayloadState() throws Exception {
        var journal = journal(); capture(0);
        UUID payment = UUID.randomUUID();
        var next = new ManagedPaymentJournalOperations.OperationUpdate(new ManagedPaymentIds.OperationId("bank", payment),
                new PaymentJson("{\"version\":8,\"state\":\"RESERVING\"}"), "RESERVING", 8L);
        assertThat(journal.saveOperation(next, 7L).block()).isZero();
        assertThat(sql).contains("tenant_id", "payment_id", "version", "payload", "state");
        assertThat(binds).contains("bank", payment, 7L, 8L, "RESERVING");
    }

    @Test
    void claimRetainsFenceAndAttemptsBoundsAndNewLeaseOwner() throws Exception {
        var journal = journal(); capture(1);var expected = effect(7L, 3);var until = Instant.parse("2026-10-24T12:00:00Z");
        assertThat(journal.claimEffect(expected, "new-actor", until).block()).isEqualTo(1);
        assertThat(sql).contains("tenant_id", "effect_id", "fence", "attempts", "owner_subject", "lease_until");
        assertThat(binds).contains("bank", expected.id().effectId(), 7L, 8L, 4, "CLAIMED", "new-actor");
    }

    @Test
    void exhaustedCountersCannotWrapOrWrite() throws Exception {
        var journal = journal();
        assertThat(journal.claimEffect(effect(Long.MAX_VALUE, 1), "actor", Instant.now()).block()).isZero();
        assertThat(journal.claimEffect(effect(1, Integer.MAX_VALUE), "actor", Instant.now()).block()).isZero();
        verifyNoInteractions(operations);
    }

    @Test
    void acknowledgementRequiresExactTenantEffectFenceAndClaimedState() throws Exception {
        var journal = journal();capture(0);var expected = effect(19L, 2);
        assertThat(journal.acknowledgeEffect(expected).block()).isZero();
        assertThat(sql).contains("tenant_id", "effect_id", "fence", "state", "owner_subject", "lease_until");
        assertThat(binds).contains("bank", expected.id().effectId(), 19L, "CLAIMED", "ACKNOWLEDGED");
        assertThat(sql).contains("owner_subject = NULL", "lease_until = NULL");
    }

    @Test
    void assignedHistoryUsesInsertAndPreservesCompositeIdentity() throws Exception {
        var journal = journal();var history = new ManagedPaymentHistoryEntity(
                new ManagedPaymentIds.EventId("bank", UUID.randomUUID(), 9L),UUID.randomUUID(),UUID.randomUUID(),new PaymentJson("{}"));
        when(operations.insert(history)).thenReturn(Mono.just(history));
        assertThat(journal.insertHistory(history).block()).isSameAs(history);
        verify(operations).insert(history);
    }

    @SuppressWarnings("unchecked")
    private Mono<Long> reschedule(ManagedPaymentJournalOperations journal,
            ManagedPaymentJournalOperations.EffectVersion expected, String state, Instant nextAttempt) throws Exception {
        var method = Arrays.stream(ManagedPaymentJournalOperations.class.getMethods())
                .filter(candidate -> candidate.getName().equals("rescheduleEffect")).findFirst().orElse(null);
        assertThat(method).as("Mapped effect rescheduling fragment").isNotNull();
        return (Mono<Long>) method.invoke(journal, expected, state, nextAttempt);
    }

    @Test
    void rescheduleBindsExactTenantEffectFenceAndClearsLeaseWithoutLosingMicroseconds() throws Exception {
        var journal = journal();capture(0);var expected = effect(19L, 2);
        var nextAttempt = Instant.parse("2026-10-30T12:00:01.123456Z");
        assertThat(reschedule(journal, expected, "EXHAUSTED", nextAttempt).block()).isZero();
        assertThat(sql).contains("tenant_id", "effect_id", "fence", "state", "next_attempt_at",
                "owner_subject = NULL", "lease_until = NULL");
        assertThat(binds).contains("bank", expected.id().effectId(), 19L, "EXHAUSTED",
                nextAttempt.atOffset(java.time.ZoneOffset.UTC));
        assertThat(sql).doesNotContain("attempts =", "fence = fence");
    }

    @Test
    void rescheduleReturnsTheActualAffectedRowCount() throws Exception {
        var journal = journal();var expected = effect(8L, 4);
        for (long affected : new long[] {0, 1, 2}) {
            capture(affected);
            assertThat(reschedule(journal, expected, "PENDING", Instant.EPOCH).block()).isEqualTo(affected);
        }
    }

    @Test
    void mappedEffectReschedulingTargetsTheCanonicalPublicRelation() throws Exception {
        var journal = journal();capture(1);
        assertThat(reschedule(journal, effect(7L, 3), "PENDING", Instant.EPOCH).block()).isEqualTo(1L);
        assertThat(sql).startsWith("UPDATE public.managed_payment_effect SET ");
    }
}
