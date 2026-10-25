-- Additive typed repository capabilities; caller privileges and existing RLS remain authoritative.
-- Ordinary journal persistence stays mapped Spring Data operations. V12/V14 remain immutable.

CREATE FUNCTION public.managed_payment_repository_lock(key text) RETURNS void
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
 SELECT pg_advisory_xact_lock(hashtextextended($1,0))
$repository$;

CREATE FUNCTION public.managed_payment_repository_now() RETURNS bigint
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
 SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint AS now
$repository$;

CREATE FUNCTION public.managed_payment_repository_defer(tenant text, effect uuid, expected_fence bigint, next_state text, delay_seconds integer) RETURNS bigint
 LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
DECLARE affected bigint;
BEGIN
 UPDATE public.managed_payment_effect SET state=$4,owner_subject=NULL,lease_until=NULL,next_attempt_at=clock_timestamp()+$5 * interval '1 second' WHERE tenant_id=$1 AND effect_id=$2 AND fence=$3;
 GET DIAGNOSTICS affected = ROW_COUNT;
 RETURN affected;
END
$repository$;

CREATE FUNCTION public.managed_payment_repository_reset(tenant text, effect uuid, expected_fence bigint) RETURNS bigint
 LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
DECLARE affected bigint;
BEGIN
 UPDATE public.managed_payment_effect SET state='PENDING',owner_subject=NULL,lease_until=NULL,next_attempt_at=clock_timestamp() WHERE tenant_id=$1 AND effect_id=$2 AND fence=$3;
 GET DIAGNOSTICS affected = ROW_COUNT;
 RETURN affected;
END
$repository$;

CREATE FUNCTION public.managed_payment_repository_queue(tenant text, after_payment uuid, page_limit integer) RETURNS SETOF public.managed_payment_operation
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
 SELECT o.* FROM public.managed_payment_operation o LEFT JOIN public.managed_payment_effect e ON e.tenant_id=o.tenant_id AND e.effect_id=(o.payload->>'pendingEffectId')::uuid WHERE o.tenant_id=$1 AND (CAST($2 AS uuid) IS NULL OR o.payment_id>CAST($2 AS uuid)) AND ((e.state IN('PENDING','CLAIMED') AND e.next_attempt_at<=clock_timestamp() AND (e.lease_until IS NULL OR e.lease_until<=clock_timestamp())) OR (o.state='RESERVED' AND o.payload->>'pendingEffectId' IS NULL AND coalesce((o.payload->>'reservationExpiresAt')::timestamptz,to_timestamp((o.payload->>'notAfterEpochMillis')::numeric/1000))<=clock_timestamp())) ORDER BY o.payment_id LIMIT $3
$repository$;

CREATE FUNCTION public.managed_payment_repository_runtime() RETURNS boolean
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
 SELECT NOT r.rolsuper AND NOT r.rolbypassrls AND NOT pg_catalog.has_schema_privilege(current_user,'public','CREATE') AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname LIKE 'managed_payment_%' AND pg_catalog.pg_get_userbyid(c.relowner)=current_user) AS allowed FROM pg_catalog.pg_roles r WHERE r.rolname=current_user
$repository$;

CREATE FUNCTION public.managed_payment_repository_context(tenant text, accounts text, purpose text, valid_until text) RETURNS void
 LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
BEGIN
 PERFORM set_config('firefly.payment.tenant',$1,true),set_config('firefly.payment.accounts',$2,true),set_config('firefly.payment.purpose',$3,true),set_config('firefly.payment.xid',pg_current_xact_id()::text,true),set_config('firefly.payment.until',$4,true);
END
$repository$;

CREATE FUNCTION public.payment_account_repository_runtime(valid_until bigint) RETURNS boolean
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
 WITH protected(name) AS (VALUES ('managed_payment_operation'),('managed_payment_action'),('managed_payment_effect'), ('managed_payment_projection'),('managed_payment_history'),('managed_payment_outbox'),('managed_payment_account_gate'), ('managed_payment_account_fence'),('managed_payment_account_action'),('managed_payment_account_history'),('managed_payment_account_outbox'), ('managed_payment_account_observation'),('managed_payment_account_observation_member'),('managed_payment_account_observation_reference')), relations AS (SELECT c.* FROM protected p JOIN pg_catalog.pg_class c ON c.relname=p.name JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public') SELECT NOT r.rolsuper AND NOT r.rolbypassrls AND NOT r.rolcreaterole AND NOT r.rolcreatedb AND NOT has_schema_privilege(current_user,'public','CREATE') AND NOT pg_has_role(current_user,'payment_closure_guard','MEMBER') AND (SELECT count(*)=14 AND bool_and(relrowsecurity AND relforcerowsecurity AND NOT pg_has_role(current_user,relowner,'MEMBER')) FROM relations) AND EXISTS(SELECT 1 FROM pg_catalog.pg_roles g WHERE g.rolname='payment_closure_guard' AND NOT g.rolsuper AND NOT g.rolbypassrls AND NOT g.rolcanlogin AND NOT g.rolcreaterole AND NOT g.rolcreatedb AND NOT g.rolinherit) AND EXISTS(SELECT 1 FROM pg_catalog.pg_proc p WHERE p.oid='public.managed_payment_account_capture(text,uuid,uuid,uuid)'::regprocedure AND p.prosecdef AND p.proowner='payment_closure_guard'::regrole AND p.proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp']) AND $1>floor(extract(epoch FROM clock_timestamp())*1000)::bigint AS allowed FROM pg_catalog.pg_roles r WHERE r.rolname=current_user
$repository$;

CREATE FUNCTION public.payment_account_repository_context(tenant text, account text, purpose text, valid_until text, issuer text, subject text) RETURNS void
 LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
BEGIN
 PERFORM set_config('firefly.payment_closure.tenant',$1,true),set_config('firefly.payment_closure.account',$2,true), set_config('firefly.payment_closure.purpose',$3,true),set_config('firefly.payment_closure.xid',pg_current_xact_id()::text,true), set_config('firefly.payment_closure.until',$4,true),set_config('firefly.payment_closure.actor_issuer',$5,true), set_config('firefly.payment_closure.actor_subject',$6,true);
END
$repository$;

CREATE FUNCTION public.payment_account_repository_latest(tenant text, account uuid, fence uuid) RETURNS SETOF public.managed_payment_account_observation
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
 SELECT * FROM public.managed_payment_account_observation WHERE tenant_id=$1 AND account_id=$2 AND fence_id=$3 ORDER BY (receipt->>'observedAt')::timestamptz DESC,observation_id DESC LIMIT 1
$repository$;
