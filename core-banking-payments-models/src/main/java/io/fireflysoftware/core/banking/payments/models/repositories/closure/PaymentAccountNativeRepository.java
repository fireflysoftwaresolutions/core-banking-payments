package io.fireflysoftware.core.banking.payments.models.repositories.closure;

import io.fireflysoftware.core.banking.payments.models.entities.closure.*;
import io.fireflysoftware.core.banking.payments.models.entities.managed.PaymentJson;
import org.springframework.data.repository.Repository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface PaymentAccountNativeRepository extends Repository<PaymentAccountObservationEntity, PaymentAccountIds.ObservationId> {
    record Receipt(PaymentJson receipt) {}
    @Modifying @Query("SELECT public.managed_payment_account_gate(:tenant,:account)")
    Mono<Void> lockAccount(@Param("tenant") String tenant, @Param("account") UUID account);
    @Query("SELECT public.managed_payment_account_quiesce(:tenant,:account,:action,:closure,:enrollment,:expected,CAST(:selection AS jsonb),:ledger,:digest) AS receipt")
    Mono<Receipt> quiesce(@Param("tenant") String tenant, @Param("account") UUID account,
            @Param("action") UUID action, @Param("closure") UUID closure, @Param("enrollment") UUID enrollment,
            @Param("expected") long expected, @Param("selection") String selection,
            @Param("ledger") long ledger, @Param("digest") String digest);
    @Query("SELECT public.managed_payment_account_release(:tenant,:account,:action,:closure,:expected,:digest) AS receipt")
    Mono<Receipt> release(@Param("tenant") String tenant, @Param("account") UUID account,
            @Param("action") UUID action, @Param("closure") UUID closure,
            @Param("expected") long expected, @Param("digest") String digest);
    @Query("SELECT public.managed_payment_account_capture(:tenant,:account,:closure,:observation) AS receipt")
    Mono<Receipt> capture(@Param("tenant") String tenant, @Param("account") UUID account,
            @Param("closure") UUID closure, @Param("observation") UUID observation);
    @Query("SELECT * FROM public.payment_account_repository_latest(:tenant,:account,:fence)")
    Mono<PaymentAccountObservationEntity> latest(@Param("tenant") String tenant,
            @Param("account") UUID account, @Param("fence") UUID fence);
    @Query("SELECT public.payment_account_repository_runtime(:until)")
    Mono<Boolean> runtimeAllowed(@Param("until") long until);
    @Modifying @Query("SELECT public.payment_account_repository_context(:tenant,:account,:purpose,:until,:issuer,:subject)")
    Mono<Void> establishContext(@Param("tenant") String tenant, @Param("account") String account,
            @Param("purpose") String purpose, @Param("until") String until,
            @Param("issuer") String issuer, @Param("subject") String subject);
}
