package io.fireflysoftware.core.banking.payments.models.entities.managed;

import java.util.UUID;
import org.springframework.data.relational.core.mapping.Column;

public final class ManagedPaymentIds {
    private ManagedPaymentIds() {}
    public record OperationId(@Column("tenant_id") String tenantId, @Column("payment_id") UUID paymentId) {}
    public record ActionId(@Column("tenant_id") String tenantId, @Column("action_id") UUID actionId) {}
    public record EffectId(@Column("tenant_id") String tenantId, @Column("effect_id") UUID effectId) {}
    public record EventId(@Column("tenant_id") String tenantId, @Column("payment_id") UUID paymentId, @Column("version") long version) {}
}
