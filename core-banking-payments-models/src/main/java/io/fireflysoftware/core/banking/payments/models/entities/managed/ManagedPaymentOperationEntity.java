package io.fireflysoftware.core.banking.payments.models.entities.managed;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("managed_payment_operation")
public record ManagedPaymentOperationEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) ManagedPaymentIds.OperationId id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        UUID createActionId,
        String requestDigest,
        PaymentJson payload,
        String state,
        Long version) {}
