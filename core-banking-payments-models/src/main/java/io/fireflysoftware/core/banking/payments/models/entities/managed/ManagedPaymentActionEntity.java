package io.fireflysoftware.core.banking.payments.models.entities.managed;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(name = "managed_payment_action", schema = "public")
public record ManagedPaymentActionEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) ManagedPaymentIds.ActionId id,
        UUID paymentId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        String kind,
        String intentDigest,
        String actorSubject,
        PaymentJson receipt) {}
