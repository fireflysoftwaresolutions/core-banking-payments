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
    @Query("SELECT pg_catalog.clock_timestamp()")
    Mono<java.time.OffsetDateTime> databaseTime();
    @Query("SELECT * FROM public.managed_payment_repository_queue(:tenant,:after,:limit)")
    Flux<ManagedPaymentOperationEntity> recoveryQueue(@Param("tenant") String tenant,
            @Param("after") UUID after, @Param("limit") int limit);
    @Query("""
            SELECT public.managed_payment_repository_runtime() AND EXISTS (
             SELECT 1 FROM pg_catalog.pg_proc p
             JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
             JOIN pg_catalog.pg_language l ON l.oid=p.prolang
             JOIN pg_catalog.pg_class c ON c.relname='managed_payment_monetary_proof' AND c.relkind='r'
             JOIN pg_catalog.pg_namespace cn ON cn.oid=c.relnamespace AND cn.nspname='public'
             WHERE n.nspname='public' AND p.proname='managed_payment_repository_runtime' AND p.pronargs=0
              AND p.proargtypes=''::pg_catalog.oidvector AND p.proargmodes IS NULL AND p.proallargtypes IS NULL
              AND p.proowner=c.relowner AND p.prorettype='pg_catalog.bool'::pg_catalog.regtype
              AND p.prokind='f' AND NOT p.proretset AND NOT p.prosecdef AND p.provolatile='v' AND l.lanname='sql'
              AND p.probin IS NULL AND p.prosqlbody IS NULL AND p.protrftypes IS NULL AND p.prosupport=0
              AND p.proconfig=ARRAY['search_path=pg_catalog, public, pg_temp']
              AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p.prosrc,'UTF8')),'hex')='a041cd5b13c63b18da572cb6ce4e68ddf6f6df02396740d529c1e26ba8f9357d')
            """)
    Mono<Boolean> runtimeAllowed();
    @Modifying @Query("SELECT public.managed_payment_repository_context(:tenant,:accounts,:purpose,:until)")
    Mono<Void> establishContext(@Param("tenant") String tenant, @Param("accounts") String accounts,
            @Param("purpose") String purpose, @Param("until") String until);
}
