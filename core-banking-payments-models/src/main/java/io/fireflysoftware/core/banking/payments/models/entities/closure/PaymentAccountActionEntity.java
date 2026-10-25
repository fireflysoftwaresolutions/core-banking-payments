package io.fireflysoftware.core.banking.payments.models.entities.closure;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;
import io.fireflysoftware.core.banking.payments.models.entities.managed.PaymentJson;

import java.util.UUID;

@Table("managed_payment_account_action")
public record PaymentAccountActionEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) PaymentAccountIds.ActionId id,
        UUID accountId,
        UUID fenceId,
        String kind,
        String intentDigest,
        PaymentJson selection,
        PaymentJson request,
        PaymentJson receipt,
        String actorIssuer,
        String actorSubject) {}
