package io.fireflysoftware.core.banking.payments.models.repositories.managed;

import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.UUID;

public interface ManagedPaymentJournalOperations {
    record OperationUpdate(ManagedPaymentIds.OperationId id, PaymentJson payload, String state, long version) {}
    record EffectVersion(ManagedPaymentIds.EffectId id, long fence, int attempts) {}
    Mono<ManagedPaymentOperationEntity> insertOperation(ManagedPaymentOperationEntity row);
    Mono<ManagedPaymentActionEntity> insertAction(ManagedPaymentActionEntity row);
    Mono<ManagedPaymentEffectEntity> insertEffect(ManagedPaymentEffectEntity row);
    Mono<ManagedPaymentProjectionEntity> insertProjection(ManagedPaymentProjectionEntity row);
    Mono<ManagedPaymentHistoryEntity> insertHistory(ManagedPaymentHistoryEntity row);
    Mono<ManagedPaymentOutboxEntity> insertOutbox(ManagedPaymentOutboxEntity row);
    Mono<Long> claimEffect(EffectVersion expected, String actor, Instant until);
    Mono<Long> saveOperation(OperationUpdate next, long priorVersion);
    Mono<Long> saveProjection(ManagedPaymentProjectionEntity next);
    Mono<Long> acknowledgeEffect(EffectVersion expected);
}
