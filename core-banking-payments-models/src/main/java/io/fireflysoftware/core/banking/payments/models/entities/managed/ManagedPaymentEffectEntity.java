package io.fireflysoftware.core.banking.payments.models.entities.managed;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.annotation.ReadOnlyProperty;
import java.time.OffsetDateTime;

import java.util.UUID;

@Table("managed_payment_effect")
public record ManagedPaymentEffectEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) ManagedPaymentIds.EffectId id,
        UUID paymentId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        UUID participantActionId,
        String kind,
        String state,
        Long fence,
        Integer attempts,
        String ownerSubject,
        OffsetDateTime leaseUntil,
        @ReadOnlyProperty OffsetDateTime nextAttemptAt) {}
