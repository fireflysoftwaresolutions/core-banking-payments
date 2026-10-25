package io.fireflysoftware.core.banking.payments.models.repositories.managed;

import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.UUID;

public interface ManagedPaymentActionRepository extends Repository<ManagedPaymentActionEntity, ManagedPaymentIds.ActionId> {
    Mono<ManagedPaymentActionEntity> findById(ManagedPaymentIds.ActionId id);
    Mono<ManagedPaymentActionEntity> findByIdTenantIdAndIdActionIdAndKind(String tenantId, UUID actionId, String kind);
}
