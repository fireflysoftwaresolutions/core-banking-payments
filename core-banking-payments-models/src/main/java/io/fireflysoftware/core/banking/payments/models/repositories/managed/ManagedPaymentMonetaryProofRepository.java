package io.fireflysoftware.core.banking.payments.models.repositories.managed;

import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import org.springframework.data.repository.Repository;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface ManagedPaymentMonetaryProofRepository
        extends Repository<ManagedPaymentMonetaryProofEntity, ManagedPaymentIds.ProofId> {
    Mono<ManagedPaymentMonetaryProofEntity> findById(ManagedPaymentIds.ProofId id);
    Mono<ManagedPaymentMonetaryProofEntity> findByIdTenantIdAndPaymentIdAndKind(
            String tenantId, UUID paymentId, String kind);
}
