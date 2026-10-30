package io.fireflysoftware.core.banking.payments.models.repositories.managed.impl;

import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentJournalOperations;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.time.ZoneOffset;

public class ManagedPaymentJournalOperationsImpl implements ManagedPaymentJournalOperations {
    private final R2dbcEntityOperations operations;
    public ManagedPaymentJournalOperationsImpl(R2dbcEntityOperations operations) { this.operations = operations; }

    public Mono<ManagedPaymentOperationEntity> insertOperation(ManagedPaymentOperationEntity row) { return operations.insert(row); }
    public Mono<ManagedPaymentActionEntity> insertAction(ManagedPaymentActionEntity row) { return operations.insert(row); }
    public Mono<ManagedPaymentEffectEntity> insertEffect(ManagedPaymentEffectEntity row) { return operations.insert(row); }
    public Mono<ManagedPaymentProjectionEntity> insertProjection(ManagedPaymentProjectionEntity row) { return operations.insert(row); }
    public Mono<ManagedPaymentHistoryEntity> insertHistory(ManagedPaymentHistoryEntity row) { return operations.insert(row); }
    public Mono<ManagedPaymentOutboxEntity> insertOutbox(ManagedPaymentOutboxEntity row) { return operations.insert(row); }

    private Criteria effect(EffectVersion expected) {
        return Criteria.where("tenant_id").is(expected.id().tenantId())
                .and("effect_id").is(expected.id().effectId()).and("fence").is(expected.fence());
    }

    public Mono<Long> claimEffect(EffectVersion expected, String actor, Instant until) {
        if (expected.fence() == Long.MAX_VALUE || expected.attempts() == Integer.MAX_VALUE) return Mono.just(0L);
        return operations.update(Query.query(effect(expected)
                        .and("fence").lessThan(Long.MAX_VALUE).and("attempts").lessThan(Integer.MAX_VALUE)),
                Update.update("state", "CLAIMED").set("fence", Math.addExact(expected.fence(), 1L))
                        .set("attempts", Math.addExact(expected.attempts(), 1)).set("ownerSubject", actor)
                        .set("leaseUntil", until.atOffset(ZoneOffset.UTC)), ManagedPaymentEffectEntity.class);
    }

    public Mono<Long> saveOperation(OperationUpdate next, long priorVersion) {
        return operations.update(Query.query(Criteria.where("tenant_id").is(next.id().tenantId())
                        .and("payment_id").is(next.id().paymentId()).and("version").is(priorVersion)),
                Update.update("payload", next.payload()).set("state", next.state()).set("version", next.version()),
                ManagedPaymentOperationEntity.class);
    }

    public Mono<Long> saveProjection(ManagedPaymentProjectionEntity next) {
        return operations.update(Query.query(Criteria.where("tenant_id").is(next.id().tenantId())
                        .and("payment_id").is(next.id().paymentId())),
                Update.update("payload", next.payload()), ManagedPaymentProjectionEntity.class);
    }

    public Mono<Long> acknowledgeEffect(EffectVersion expected) {
        return operations.update(Query.query(effect(expected).and("state").is("CLAIMED")),
                Update.update("state", "ACKNOWLEDGED").set("ownerSubject", null).set("leaseUntil", null),
                ManagedPaymentEffectEntity.class);
    }

    public Mono<Long> rescheduleEffect(EffectVersion expected, String state, Instant nextAttempt) {
        return operations.update(Query.query(effect(expected)),
                Update.update("state", state).set("ownerSubject", null).set("leaseUntil", null)
                        .set("nextAttemptAt", nextAttempt.atOffset(ZoneOffset.UTC)), ManagedPaymentEffectEntity.class);
    }
}
