package io.fireflysoftware.core.banking.payments.models.repositories.managed;

import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.UUID;

public interface ManagedPaymentOperationRepository extends Repository<ManagedPaymentOperationEntity, ManagedPaymentIds.OperationId> {
    Mono<ManagedPaymentOperationEntity> findById(ManagedPaymentIds.OperationId id);
}
