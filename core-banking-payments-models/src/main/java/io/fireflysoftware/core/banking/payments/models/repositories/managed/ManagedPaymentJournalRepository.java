package io.fireflysoftware.core.banking.payments.models.repositories.managed;

import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import org.springframework.data.repository.Repository;

public interface ManagedPaymentJournalRepository extends Repository<ManagedPaymentOperationEntity, ManagedPaymentIds.OperationId>, ManagedPaymentJournalOperations {}
