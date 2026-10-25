package io.fireflysoftware.core.banking.payments.models.repositories.closure;

import io.fireflysoftware.core.banking.payments.models.entities.closure.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.UUID;

public interface PaymentAccountObservationMemberRepository extends Repository<PaymentAccountObservationMemberEntity, PaymentAccountIds.MemberId> {
    Flux<PaymentAccountObservationMemberEntity> findByIdTenantIdAndIdAccountIdAndIdObservationIdAndOrdinalGreaterThanOrderByOrdinalAsc(String tenantId, UUID accountId, UUID observationId, long ordinal, Pageable page);
}
