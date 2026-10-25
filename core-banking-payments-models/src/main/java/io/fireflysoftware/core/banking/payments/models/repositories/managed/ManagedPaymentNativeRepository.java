package io.fireflysoftware.core.banking.payments.models.repositories.managed;

import io.fireflysoftware.core.banking.payments.models.entities.managed.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.UUID;

public interface ManagedPaymentNativeRepository extends Repository<ManagedPaymentOperationEntity, ManagedPaymentIds.OperationId> {
    @Modifying @Query("SELECT public.managed_payment_repository_lock(:key)")
    Mono<Void> lockPayment(@Param("key") String key);
    @Modifying @Query("SELECT public.managed_payment_account_gate(:tenant,:account)")
    Mono<Void> lockAccount(@Param("tenant") String tenant, @Param("account") UUID account);
    @Query("SELECT public.managed_payment_repository_now()")
    Mono<Long> now();
    @Query("SELECT public.managed_payment_repository_defer(:tenant,:effect,:fence,:state,:delay)")
    Mono<Long> deferEffect(@Param("tenant") String tenant, @Param("effect") UUID effect,
            @Param("fence") long fence, @Param("state") String state, @Param("delay") int delay);
    @Query("SELECT public.managed_payment_repository_reset(:tenant,:effect,:fence)")
    Mono<Long> resetEffect(@Param("tenant") String tenant, @Param("effect") UUID effect, @Param("fence") long fence);
    @Query("SELECT * FROM public.managed_payment_repository_queue(:tenant,:after,:limit)")
    Flux<ManagedPaymentOperationEntity> recoveryQueue(@Param("tenant") String tenant,
            @Param("after") UUID after, @Param("limit") int limit);
    @Query("SELECT public.managed_payment_repository_runtime()")
    Mono<Boolean> runtimeAllowed();
    @Modifying @Query("SELECT public.managed_payment_repository_context(:tenant,:accounts,:purpose,:until)")
    Mono<Void> establishContext(@Param("tenant") String tenant, @Param("accounts") String accounts,
            @Param("purpose") String purpose, @Param("until") String until);
}
