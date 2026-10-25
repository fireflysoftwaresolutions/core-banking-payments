package io.fireflysoftware.core.banking.payments.models.entities.closure;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;
import io.fireflysoftware.core.banking.payments.models.entities.managed.PaymentJson;

import java.util.UUID;

@Table("managed_payment_account_observation_member")
public record PaymentAccountObservationMemberEntity(
        @Id @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY) PaymentAccountIds.MemberId id,
        Long ordinal,
        String direction,
        String state,
        String effectState,
        Boolean blocking,
        Long operationVersion) {}
