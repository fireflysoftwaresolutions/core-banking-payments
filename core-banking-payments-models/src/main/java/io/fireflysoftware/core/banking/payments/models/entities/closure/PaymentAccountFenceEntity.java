package io.fireflysoftware.core.banking.payments.models.entities.closure;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;
import io.fireflysoftware.core.banking.payments.models.entities.managed.PaymentJson;

import java.util.UUID;

@Table("managed_payment_account_fence")
public record PaymentAccountFenceEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) PaymentAccountIds.FenceId id,
        UUID closureId,
        PaymentJson selection,
        PaymentJson request,
        Long ledgerVersion,
        String state,
        Long version,
        Long sourceRevision,
        UUID lastActionId,
        PaymentJson acceptance) {}
