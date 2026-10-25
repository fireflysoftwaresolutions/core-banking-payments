package io.fireflysoftware.core.banking.payments.models.entities.managed;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("managed_payment_history")
public record ManagedPaymentHistoryEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) ManagedPaymentIds.EventId id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        PaymentJson payload) {}
