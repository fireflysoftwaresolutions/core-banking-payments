package io.fireflysoftware.core.banking.payments.models.repositories.managed;

import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.UUID;

public interface ManagedPaymentEffectRepository extends Repository<ManagedPaymentEffectEntity, ManagedPaymentIds.EffectId> {
    Mono<ManagedPaymentEffectEntity> findById(ManagedPaymentIds.EffectId id);
    Mono<ManagedPaymentEffectEntity> findByIdTenantIdAndPaymentIdAndKind(String tenantId, UUID paymentId, String kind);
}
