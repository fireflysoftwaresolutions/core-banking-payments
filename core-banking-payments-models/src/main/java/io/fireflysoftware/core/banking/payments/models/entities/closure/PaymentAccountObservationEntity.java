package io.fireflysoftware.core.banking.payments.models.entities.closure;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;
import io.fireflysoftware.core.banking.payments.models.entities.managed.PaymentJson;

import java.util.UUID;

@Table("managed_payment_account_observation")
public record PaymentAccountObservationEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) PaymentAccountIds.ObservationId id,
        UUID fenceId,
        Long fenceVersion,
        Long sourceRevision,
        PaymentJson selection,
        String status,
        Long total,
        Long obligationCount,
        PaymentJson receipt) {}
