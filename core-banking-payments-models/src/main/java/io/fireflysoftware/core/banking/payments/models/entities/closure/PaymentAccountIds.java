package io.fireflysoftware.core.banking.payments.models.entities.closure;

import java.util.UUID;
import org.springframework.data.relational.core.mapping.Column;

public final class PaymentAccountIds {
    private PaymentAccountIds() {}
    public record ActionId(@Column("tenant_id") String tenantId, @Column("action_id") UUID actionId) {}
    public record FenceId(@Column("tenant_id") String tenantId, @Column("account_id") UUID accountId, @Column("fence_id") UUID fenceId) {}
    public record ObservationId(@Column("tenant_id") String tenantId, @Column("account_id") UUID accountId, @Column("observation_id") UUID observationId) {}
    public record MemberId(@Column("tenant_id") String tenantId, @Column("account_id") UUID accountId, @Column("observation_id") UUID observationId, @Column("obligation_id") UUID obligationId) {}
}
