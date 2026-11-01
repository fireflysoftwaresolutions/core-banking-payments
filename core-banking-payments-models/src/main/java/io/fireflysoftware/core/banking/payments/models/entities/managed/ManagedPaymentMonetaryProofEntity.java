package io.fireflysoftware.core.banking.payments.models.entities.managed;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Table(name = "managed_payment_monetary_proof", schema = "public")
public record ManagedPaymentMonetaryProofEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) ManagedPaymentIds.ProofId id,
        UUID paymentId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        UUID participantActionId,
        String kind,
        UUID receiptId,
        Long retainedVersion,
        Long captureFence,
        Long observedReservationVersion,
        OffsetDateTime observedAt,
        String observerSubject,
        PaymentJson receiptEnvelope,
        @ReadOnlyProperty OffsetDateTime retainedAt) {}
