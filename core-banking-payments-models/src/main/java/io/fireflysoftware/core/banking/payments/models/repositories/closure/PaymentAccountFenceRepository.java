package io.fireflysoftware.core.banking.payments.models.repositories.closure;

import io.fireflysoftware.core.banking.payments.models.entities.closure.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.UUID;

public interface PaymentAccountFenceRepository extends Repository<PaymentAccountFenceEntity, PaymentAccountIds.FenceId> {
    Mono<PaymentAccountFenceEntity> findById(PaymentAccountIds.FenceId id);
    Mono<PaymentAccountFenceEntity> findByIdTenantIdAndIdAccountIdAndClosureId(String tenantId, UUID accountId, UUID closureId);
}
