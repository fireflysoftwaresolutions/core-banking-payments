package io.fireflysoftware.core.banking.payments.models.entities.managed;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(name = "managed_payment_projection", schema = "public")
public record ManagedPaymentProjectionEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) ManagedPaymentIds.OperationId id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        PaymentJson payload) {}
