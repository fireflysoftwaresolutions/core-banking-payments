-- Forward Flyway migration in the normal db/migration resource path.
-- Source qualification does not establish database safety; owned-database qualification is required before deployment.
-- Existing V12/V14/V15 guards, lock order and closure authority remain unchanged.
ALTER TABLE public.managed_payment_operation
 DROP CONSTRAINT managed_payment_operation_state_check,
 ADD CONSTRAINT managed_payment_operation_state_check CHECK(state IN(
  'RECEIVED','RESERVING','RESERVED','COMMITTING','CANCELLING','COMPLETED','REVERSING',
  'REVERSED','CANCELLED','EXPIRED','RECOVERY_REQUIRED')),
 DROP CONSTRAINT managed_payment_operation_check4,
 ADD CONSTRAINT managed_payment_operation_check4 CHECK(
  state NOT IN('RESERVED','COMMITTING','CANCELLING','COMPLETED','REVERSING','REVERSED','CANCELLED')
  OR (payload->>'reservationId')::uuid IS NOT NULL);
ALTER TABLE public.managed_payment_action
 DROP CONSTRAINT managed_payment_action_kind_check,
 ADD CONSTRAINT managed_payment_action_kind_check CHECK(kind IN('CREATE','CONFIRM','CANCEL','RECOVER','CLAIM','REVERSE'));
ALTER TABLE public.managed_payment_effect
 DROP CONSTRAINT managed_payment_effect_kind_check,
 ADD CONSTRAINT managed_payment_effect_kind_check CHECK(kind IN('RESERVE','COMMIT','RELEASE','OBSERVE','REVERSE')),
 ADD COLUMN original_effect_id uuid,
 ADD COLUMN original_commit_version bigint,
 ADD COLUMN original_reservation_id uuid,
 ADD CONSTRAINT managed_reversal_origin_shape CHECK(
  (kind='REVERSE' AND original_effect_id IS NOT NULL AND original_commit_version IS NOT NULL
   AND original_commit_version>=0 AND original_reservation_id IS NOT NULL AND original_effect_id<>effect_id)
  OR (kind<>'REVERSE' AND original_effect_id IS NULL AND original_commit_version IS NULL AND original_reservation_id IS NULL)),
 ADD CONSTRAINT managed_reversal_original_effect FOREIGN KEY(tenant_id,original_effect_id)
  REFERENCES public.managed_payment_effect(tenant_id,effect_id) DEFERRABLE INITIALLY DEFERRED,
 ADD CONSTRAINT managed_reversal_original_history FOREIGN KEY(tenant_id,payment_id,original_commit_version)
  REFERENCES public.managed_payment_history(tenant_id,payment_id,version) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.managed_payment_monetary_proof (
 tenant_id varchar(64) NOT NULL,payment_id uuid NOT NULL,
 source_account_id uuid NOT NULL,destination_account_id uuid NOT NULL,
 effect_id uuid NOT NULL,participant_action_id uuid NOT NULL,kind varchar(16) NOT NULL CHECK(kind IN('COMMIT','REVERSE')),
 receipt_id uuid NOT NULL,retained_version bigint NOT NULL CHECK(retained_version>0),
 capture_fence bigint NOT NULL CHECK(capture_fence>=0),
 observed_reservation_version bigint NOT NULL CHECK(observed_reservation_version>=0),
 observed_at timestamptz NOT NULL,observer_subject varchar(255) NOT NULL CHECK(length(observer_subject)>0),
 receipt_envelope jsonb NOT NULL,retained_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT managed_monetary_proof_id PRIMARY KEY(tenant_id,effect_id),
 CONSTRAINT managed_monetary_proof_receipt UNIQUE(tenant_id,receipt_id),
 CONSTRAINT managed_monetary_proof_effect FOREIGN KEY(tenant_id,effect_id)
  REFERENCES public.managed_payment_effect(tenant_id,effect_id) DEFERRABLE INITIALLY DEFERRED,
 CONSTRAINT managed_monetary_proof_pair FOREIGN KEY(tenant_id,payment_id,source_account_id,destination_account_id)
  REFERENCES public.managed_payment_operation(tenant_id,payment_id,source_account_id,destination_account_id),
 CONSTRAINT managed_monetary_proof_history FOREIGN KEY(tenant_id,payment_id,retained_version)
  REFERENCES public.managed_payment_history(tenant_id,payment_id,version) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE public.managed_payment_monetary_proof ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.managed_payment_monetary_proof FORCE ROW LEVEL SECURITY;
CREATE POLICY monetary_proof_read ON public.managed_payment_monetary_proof FOR SELECT
 USING(public.managed_payment_scope(tenant_id,source_account_id,destination_account_id));
CREATE POLICY monetary_proof_insert ON public.managed_payment_monetary_proof FOR INSERT WITH CHECK(
 public.managed_payment_scope(tenant_id,source_account_id,destination_account_id)
 AND current_setting('firefly.payment.purpose',true)='OBSERVE'
 AND current_setting('firefly.payment.xid',true)=pg_current_xact_id_if_assigned()::text
 AND coalesce(nullif(current_setting('firefly.payment.until',true),''),'0')::bigint>
  floor(extract(epoch FROM clock_timestamp())*1000)::bigint);

CREATE FUNCTION public.guard_managed_payment_reversal_origin() RETURNS trigger
 LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $reversal$
DECLARE value jsonb; form jsonb;
BEGIN
 IF TG_TABLE_NAME='managed_payment_monetary_proof' THEN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'IMMUTABLE_PAYMENT_MONETARY_PROOF'; END IF;
  IF current_setting('firefly.payment.purpose',true) IS DISTINCT FROM 'OBSERVE'
  THEN RAISE EXCEPTION 'VERIFIED_PAYMENT_MONETARY_PROOF_REQUIRED'; END IF;
  value=NEW.receipt_envelope->'value'; form=NEW.receipt_envelope->'canonical';
  IF jsonb_typeof(NEW.receipt_envelope) IS DISTINCT FROM 'object'
   OR jsonb_typeof(value) IS DISTINCT FROM 'object' OR jsonb_typeof(form) IS DISTINCT FROM 'object'
   OR octet_length(NEW.receipt_envelope::text)>2097152
   OR jsonb_typeof(value->'acceptedReserve') IS DISTINCT FROM 'object'
   OR jsonb_typeof(value->'effect') IS DISTINCT FROM 'object'
   OR value->>'reservationId' IS NULL OR value->>'postingId' IS NULL
   OR value#>>'{acceptedReserve,intent,amount}' IS NULL
   OR value#>>'{acceptedReserve,intent,currency}' IS NULL
   OR value#>>'{acceptedReserve,source,enrollmentId}' IS NULL
   OR value#>>'{acceptedReserve,destination,enrollmentId}' IS NULL
   OR value#>>'{acceptedReserve,source,contractId}' IS NULL
   OR value#>>'{acceptedReserve,destination,contractId}' IS NULL
   OR value#>>'{actor,issuer}' IS NULL OR value#>>'{actor,subject}' IS NULL
   OR form->>'encoding' IS DISTINCT FROM 'FND_MONETARY_RECEIPT_V1'
   OR form->>'digest' IS NULL OR form->>'digest' !~ '^[a-f0-9]{64}$'
   OR form->>'canonical' IS NULL OR length(form->>'canonical') NOT BETWEEN 1 AND 1048576
   OR encode(sha256(decode(form->>'canonical','base64')),'hex') IS DISTINCT FROM form->>'digest'
   OR (value->>'receiptId')::uuid IS DISTINCT FROM NEW.receipt_id
   OR (value->>'eventId')::uuid IS DISTINCT FROM NEW.receipt_id
   OR value->>'tenant' IS DISTINCT FROM NEW.tenant_id
   OR (value->>'operationId')::uuid IS DISTINCT FROM NEW.payment_id
   OR (value->>'actionId')::uuid IS DISTINCT FROM NEW.participant_action_id
   OR value->>'effectKind' IS DISTINCT FROM NEW.kind
   OR value#>>'{acceptedReserve,tenant}' IS DISTINCT FROM NEW.tenant_id
   OR (value#>>'{acceptedReserve,reservationId}')::uuid IS DISTINCT FROM (value->>'reservationId')::uuid
   OR (value#>>'{acceptedReserve,intent,operationId}')::uuid IS DISTINCT FROM NEW.payment_id
   OR (value#>>'{acceptedReserve,intent,sourceAccountId}')::uuid IS DISTINCT FROM NEW.source_account_id
   OR (value#>>'{acceptedReserve,intent,destinationAccountId}')::uuid IS DISTINCT FROM NEW.destination_account_id
   OR (value#>>'{acceptedReserve,source,accountId}')::uuid IS DISTINCT FROM NEW.source_account_id
   OR (value#>>'{acceptedReserve,destination,accountId}')::uuid IS DISTINCT FROM NEW.destination_account_id
   OR value#>'{effect,source}' IS DISTINCT FROM value#>'{acceptedReserve,source}'
   OR value#>'{effect,destination}' IS DISTINCT FROM value#>'{acceptedReserve,destination}'
   OR (value#>>'{effect,amount}')::numeric IS DISTINCT FROM (value#>>'{acceptedReserve,intent,amount}')::numeric
   OR value#>>'{effect,currency}' IS DISTINCT FROM value#>>'{acceptedReserve,intent,currency}'
   OR (value#>>'{effect,fromAccountId}')::uuid IS DISTINCT FROM
    (CASE WHEN NEW.kind='COMMIT' THEN NEW.source_account_id ELSE NEW.destination_account_id END)
   OR (value#>>'{effect,toAccountId}')::uuid IS DISTINCT FROM
    (CASE WHEN NEW.kind='COMMIT' THEN NEW.destination_account_id ELSE NEW.source_account_id END)
  THEN RAISE EXCEPTION 'EXACT_PAYMENT_MONETARY_PROOF_REQUIRED'; END IF;
  NEW.retained_at=clock_timestamp();
 ELSE
  IF TG_OP='UPDATE' AND ROW(NEW.original_effect_id,NEW.original_commit_version,NEW.original_reservation_id)
    IS DISTINCT FROM ROW(OLD.original_effect_id,OLD.original_commit_version,OLD.original_reservation_id)
  THEN RAISE EXCEPTION 'IMMUTABLE_PAYMENT_REVERSE_ORIGIN'; END IF;
  IF TG_OP='INSERT' AND NEW.kind='REVERSE'
   AND current_setting('firefly.payment.purpose',true) IS DISTINCT FROM 'OBSERVE'
  THEN RAISE EXCEPTION 'VERIFIED_PAYMENT_MONETARY_PROOF_REQUIRED'; END IF;
 END IF;
 RETURN NEW;
END
$reversal$;
CREATE TRIGGER managed_monetary_proof_guard BEFORE INSERT OR UPDATE OR DELETE
 ON public.managed_payment_monetary_proof FOR EACH ROW EXECUTE FUNCTION public.guard_managed_payment_reversal_origin();
CREATE TRIGGER managed_reversal_origin_guard BEFORE INSERT OR UPDATE
 ON public.managed_payment_effect FOR EACH ROW EXECUTE FUNCTION public.guard_managed_payment_reversal_origin();

CREATE FUNCTION public.require_complete_managed_payment_reversal() RETURNS trigger
 LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $reversal$
DECLARE parent public.managed_payment_operation; reversal public.managed_payment_effect;
 original public.managed_payment_effect; commit_proof public.managed_payment_monetary_proof;
 reverse_proof public.managed_payment_monetary_proof; committed public.managed_payment_history;
 admitted public.managed_payment_history; acknowledged public.managed_payment_history;
 command public.managed_payment_action; value jsonb; prior_value jsonb;
BEGIN
 SELECT * INTO parent FROM public.managed_payment_operation WHERE tenant_id=NEW.tenant_id AND payment_id=NEW.payment_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'RETAINED_PAYMENT_PARENT_REQUIRED'; END IF;
 SELECT * INTO reversal FROM public.managed_payment_effect
  WHERE tenant_id=parent.tenant_id AND payment_id=parent.payment_id AND kind='REVERSE';
 IF NOT FOUND THEN
  IF TG_TABLE_NAME='managed_payment_action' THEN
   IF NEW.kind='REVERSE' THEN RAISE EXCEPTION 'COMPLETE_PAYMENT_REVERSAL_REQUIRED'; END IF;
  ELSIF TG_TABLE_NAME IN('managed_payment_history','managed_payment_outbox') THEN
   IF NEW.payload->>'type' IN('REVERSE_ACCEPTED','LEDGER_REVERSED')
   THEN RAISE EXCEPTION 'COMPLETE_PAYMENT_REVERSAL_REQUIRED'; END IF;
  END IF;
  IF parent.state IN('REVERSING','REVERSED') OR EXISTS(SELECT 1 FROM public.managed_payment_monetary_proof
   WHERE tenant_id=parent.tenant_id AND payment_id=parent.payment_id)
  THEN RAISE EXCEPTION 'COMPLETE_PAYMENT_REVERSAL_REQUIRED'; END IF;
  RETURN NULL;
 END IF;
 SELECT * INTO original FROM public.managed_payment_effect
  WHERE tenant_id=parent.tenant_id AND effect_id=reversal.original_effect_id;
 IF NOT FOUND OR original.payment_id IS DISTINCT FROM parent.payment_id OR original.kind IS DISTINCT FROM 'COMMIT'
  OR original.state IS DISTINCT FROM 'ACKNOWLEDGED'
  OR ROW(reversal.source_account_id,reversal.destination_account_id,original.source_account_id,original.destination_account_id)
    IS DISTINCT FROM ROW(parent.source_account_id,parent.destination_account_id,parent.source_account_id,parent.destination_account_id)
  OR reversal.original_reservation_id IS DISTINCT FROM (parent.payload->>'reservationId')::uuid
 THEN RAISE EXCEPTION 'EXACT_PAYMENT_COMMIT_ORIGIN_REQUIRED'; END IF;
 IF EXISTS(SELECT 1 FROM public.managed_payment_monetary_proof WHERE tenant_id=parent.tenant_id
  AND payment_id=parent.payment_id AND (effect_id NOT IN(original.effect_id,reversal.effect_id)
   OR kind IS DISTINCT FROM CASE WHEN effect_id=original.effect_id THEN 'COMMIT' ELSE 'REVERSE' END))
 THEN RAISE EXCEPTION 'EXACT_PAYMENT_MONETARY_PROOF_REQUIRED'; END IF;
 SELECT * INTO committed FROM public.managed_payment_history
  WHERE tenant_id=parent.tenant_id AND payment_id=parent.payment_id AND version=reversal.original_commit_version;
 IF NOT FOUND OR committed.payload->>'type' IS DISTINCT FROM 'LEDGER_COMMITTED'
  OR committed.payload->>'state' IS DISTINCT FROM 'COMPLETED'
  OR (committed.payload->>'version')::bigint IS DISTINCT FROM committed.version
  OR (committed.payload->>'effectId')::uuid IS DISTINCT FROM original.effect_id
  OR (committed.payload->>'postingId')::uuid IS DISTINCT FROM (parent.payload->>'postingId')::uuid
  OR NOT EXISTS(SELECT 1 FROM public.managed_payment_outbox WHERE tenant_id=parent.tenant_id
    AND payment_id=parent.payment_id AND version=committed.version AND payload=committed.payload)
 THEN RAISE EXCEPTION 'EXACT_PAYMENT_COMMIT_ORIGIN_REQUIRED'; END IF;
 SELECT * INTO commit_proof FROM public.managed_payment_monetary_proof
  WHERE tenant_id=parent.tenant_id AND effect_id=original.effect_id;
 IF NOT FOUND OR commit_proof.kind IS DISTINCT FROM 'COMMIT' OR commit_proof.payment_id IS DISTINCT FROM parent.payment_id
  OR commit_proof.participant_action_id IS DISTINCT FROM original.participant_action_id
  OR commit_proof.capture_fence IS DISTINCT FROM original.fence
  OR ROW(commit_proof.source_account_id,commit_proof.destination_account_id)
    IS DISTINCT FROM ROW(parent.source_account_id,parent.destination_account_id)
  OR commit_proof.retained_version<=committed.version OR commit_proof.retained_version>parent.version
 THEN RAISE EXCEPTION 'EXACT_PAYMENT_COMMIT_PROOF_REQUIRED'; END IF;
 prior_value=commit_proof.receipt_envelope->'value';
 IF (prior_value->>'reservationId')::uuid IS DISTINCT FROM reversal.original_reservation_id
  OR (prior_value->>'postingId')::uuid IS DISTINCT FROM (parent.payload->>'postingId')::uuid
  OR prior_value->>'originalCommitReceiptId' IS NOT NULL OR prior_value->>'originalPostingId' IS NOT NULL
  OR (prior_value#>>'{acceptedReserve,intent,amount}')::numeric IS DISTINCT FROM (parent.payload->>'amount')::numeric
  OR prior_value#>>'{acceptedReserve,intent,currency}' IS DISTINCT FROM parent.payload->>'currency'
  OR (prior_value#>>'{acceptedReserve,intent,ttlSeconds}')::int IS DISTINCT FROM (parent.payload->>'ttlSeconds')::int
  OR (prior_value#>>'{acceptedReserve,intent,notAfterEpochMillis}')::bigint IS DISTINCT FROM (parent.payload->>'notAfterEpochMillis')::bigint
  OR (prior_value#>>'{acceptedReserve,source,currencyScale}')::int IS DISTINCT FROM (parent.payload->>'currencyScale')::int
  OR (prior_value#>>'{acceptedReserve,destination,currencyScale}')::int IS DISTINCT FROM (parent.payload->>'currencyScale')::int
  OR prior_value#>>'{acceptedReserve,source,currencyPolicy}' IS DISTINCT FROM parent.payload->>'currencyPolicy'
  OR prior_value#>>'{acceptedReserve,destination,currencyPolicy}' IS DISTINCT FROM parent.payload->>'currencyPolicy'
 THEN RAISE EXCEPTION 'EXACT_PAYMENT_COMMIT_PROOF_REQUIRED'; END IF;
 SELECT * INTO admitted FROM public.managed_payment_history WHERE tenant_id=parent.tenant_id
  AND payment_id=parent.payment_id AND version=commit_proof.retained_version;
 IF NOT FOUND OR admitted.payload->>'type' IS DISTINCT FROM 'REVERSE_ACCEPTED'
  OR admitted.payload->>'state' IS DISTINCT FROM 'REVERSING'
  OR (admitted.payload->>'version')::bigint IS DISTINCT FROM admitted.version
  OR admitted.payload->>'actor' IS DISTINCT FROM commit_proof.observer_subject
  OR (admitted.payload->>'effectId')::uuid IS DISTINCT FROM reversal.effect_id
  OR (admitted.payload->>'postingId')::uuid IS DISTINCT FROM (parent.payload->>'postingId')::uuid
  OR NOT EXISTS(SELECT 1 FROM public.managed_payment_outbox WHERE tenant_id=parent.tenant_id
    AND payment_id=parent.payment_id AND version=admitted.version AND payload=admitted.payload)
 THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_REVERSE_ADMISSION_REQUIRED'; END IF;
 SELECT * INTO command FROM public.managed_payment_action WHERE tenant_id=parent.tenant_id
  AND action_id=(admitted.payload->>'actionId')::uuid;
 IF NOT FOUND OR command.payment_id IS DISTINCT FROM parent.payment_id OR command.kind IS DISTINCT FROM 'REVERSE'
  OR command.actor_subject IS DISTINCT FROM commit_proof.observer_subject
  OR ROW(command.source_account_id,command.destination_account_id) IS DISTINCT FROM ROW(parent.source_account_id,parent.destination_account_id)
  OR command.intent_digest IS DISTINCT FROM encode(sha256(convert_to('REVERSE|'||parent.payment_id||'|'||(admitted.version-1),'UTF8')),'hex')
 THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_REVERSE_ADMISSION_REQUIRED'; END IF;
 IF TG_TABLE_NAME='managed_payment_action' THEN
  IF NEW.kind='REVERSE' AND NEW.action_id IS DISTINCT FROM command.action_id
  THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_REVERSE_ADMISSION_REQUIRED'; END IF;
 ELSIF TG_TABLE_NAME IN('managed_payment_history','managed_payment_outbox') THEN
  IF NEW.payload->>'type'='REVERSE_ACCEPTED' AND ROW(NEW.version,NEW.payload)
    IS DISTINCT FROM ROW(admitted.version,admitted.payload)
  THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_REVERSE_ADMISSION_REQUIRED'; END IF;
 END IF;
 SELECT * INTO reverse_proof FROM public.managed_payment_monetary_proof
  WHERE tenant_id=parent.tenant_id AND effect_id=reversal.effect_id;
 IF reversal.state<>'ACKNOWLEDGED' THEN
  IF FOUND OR parent.state NOT IN('REVERSING','RECOVERY_REQUIRED')
   OR (parent.payload->>'pendingEffectId')::uuid IS DISTINCT FROM reversal.effect_id
  THEN RAISE EXCEPTION 'COMPLETE_PAYMENT_REVERSAL_REQUIRED'; END IF;
  IF TG_TABLE_NAME IN('managed_payment_history','managed_payment_outbox') THEN
   IF NEW.payload->>'type'='LEDGER_REVERSED' THEN RAISE EXCEPTION 'COMPLETE_PAYMENT_REVERSAL_REQUIRED'; END IF;
  END IF;
  RETURN NULL;
 END IF;
 IF NOT FOUND OR reverse_proof.kind IS DISTINCT FROM 'REVERSE' OR reverse_proof.payment_id IS DISTINCT FROM parent.payment_id
  OR reverse_proof.participant_action_id IS DISTINCT FROM reversal.participant_action_id
  OR reverse_proof.capture_fence IS DISTINCT FROM reversal.fence
  OR ROW(reverse_proof.source_account_id,reverse_proof.destination_account_id) IS DISTINCT FROM ROW(parent.source_account_id,parent.destination_account_id)
  OR reverse_proof.retained_version<=admitted.version OR reverse_proof.retained_version>parent.version
  OR reverse_proof.observed_reservation_version<commit_proof.observed_reservation_version
  OR parent.state IS DISTINCT FROM 'REVERSED' OR parent.payload->>'pendingEffectId' IS NOT NULL
 THEN RAISE EXCEPTION 'EXACT_PAYMENT_REVERSE_PROOF_REQUIRED'; END IF;
 value=reverse_proof.receipt_envelope->'value';
 IF (value->>'reservationId')::uuid IS DISTINCT FROM reversal.original_reservation_id
  OR (value->>'originalCommitReceiptId')::uuid IS DISTINCT FROM commit_proof.receipt_id
  OR value->>'originalPostingId' IS DISTINCT FROM prior_value->>'postingId'
  OR value->'acceptedReserve' IS DISTINCT FROM prior_value->'acceptedReserve'
  OR (value->>'postingId')::uuid IS DISTINCT FROM (parent.payload->>'reversalId')::uuid
  OR (parent.payload->>'ledgerVersion')::bigint IS DISTINCT FROM reverse_proof.observed_reservation_version
 THEN RAISE EXCEPTION 'EXACT_PAYMENT_REVERSE_PROOF_REQUIRED'; END IF;
 SELECT * INTO acknowledged FROM public.managed_payment_history WHERE tenant_id=parent.tenant_id
  AND payment_id=parent.payment_id AND version=reverse_proof.retained_version;
 IF NOT FOUND OR acknowledged.payload->>'type' IS DISTINCT FROM 'LEDGER_REVERSED'
  OR acknowledged.payload->>'state' IS DISTINCT FROM 'REVERSED'
  OR (acknowledged.payload->>'version')::bigint IS DISTINCT FROM acknowledged.version
  OR acknowledged.payload->>'actor' IS DISTINCT FROM reverse_proof.observer_subject
  OR (acknowledged.payload->>'effectId')::uuid IS DISTINCT FROM reversal.effect_id
  OR (acknowledged.payload->>'postingId')::uuid IS DISTINCT FROM (parent.payload->>'postingId')::uuid
  OR (acknowledged.payload->>'reversalId')::uuid IS DISTINCT FROM (parent.payload->>'reversalId')::uuid
  OR NOT EXISTS(SELECT 1 FROM public.managed_payment_outbox WHERE tenant_id=parent.tenant_id
    AND payment_id=parent.payment_id AND version=acknowledged.version AND payload=acknowledged.payload)
 THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_REVERSE_ACKNOWLEDGMENT_REQUIRED'; END IF;
 IF TG_TABLE_NAME IN('managed_payment_history','managed_payment_outbox') THEN
  IF NEW.payload->>'type'='LEDGER_REVERSED' AND ROW(NEW.version,NEW.payload)
    IS DISTINCT FROM ROW(acknowledged.version,acknowledged.payload)
  THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_REVERSE_ACKNOWLEDGMENT_REQUIRED'; END IF;
 END IF;
 RETURN NULL;
END
$reversal$;
CREATE CONSTRAINT TRIGGER managed_reversal_operation_complete AFTER INSERT OR UPDATE ON public.managed_payment_operation
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_managed_payment_reversal();
CREATE CONSTRAINT TRIGGER managed_reversal_effect_complete AFTER INSERT OR UPDATE ON public.managed_payment_effect
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_managed_payment_reversal();
CREATE CONSTRAINT TRIGGER managed_reversal_proof_complete AFTER INSERT ON public.managed_payment_monetary_proof
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_managed_payment_reversal();
CREATE CONSTRAINT TRIGGER managed_reversal_action_complete AFTER INSERT ON public.managed_payment_action
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_managed_payment_reversal();
CREATE CONSTRAINT TRIGGER managed_reversal_history_complete AFTER INSERT ON public.managed_payment_history
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_managed_payment_reversal();
CREATE CONSTRAINT TRIGGER managed_reversal_outbox_complete AFTER INSERT ON public.managed_payment_outbox
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_managed_payment_reversal();

CREATE OR REPLACE FUNCTION public.managed_payment_repository_runtime() RETURNS boolean
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
 WITH protected(name) AS (VALUES ('managed_payment_operation'),('managed_payment_action'),('managed_payment_effect'), ('managed_payment_projection'),('managed_payment_history'),('managed_payment_outbox'),('managed_payment_account_gate'), ('managed_payment_account_fence'),('managed_payment_account_action'),('managed_payment_account_history'),('managed_payment_account_outbox'), ('managed_payment_account_observation'),('managed_payment_account_observation_member'),('managed_payment_account_observation_reference'),('managed_payment_monetary_proof')), relations AS (SELECT c.* FROM protected p JOIN pg_catalog.pg_class c ON c.relname=p.name JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public') SELECT NOT r.rolsuper AND NOT r.rolbypassrls AND NOT pg_catalog.has_database_privilege(current_user,current_database(),'CREATE') AND NOT pg_catalog.has_database_privilege(current_user,current_database(),'TEMP') AND NOT r.rolcreaterole AND NOT r.rolcreatedb AND NOT has_schema_privilege(current_user,'public','CREATE') AND NOT pg_has_role(current_user,'payment_closure_guard','MEMBER') AND (SELECT count(*)=15 AND bool_and(relrowsecurity AND relforcerowsecurity AND NOT pg_has_role(current_user,relowner,'MEMBER')) FROM relations) AND EXISTS(SELECT 1 FROM pg_catalog.pg_roles g WHERE g.rolname='payment_closure_guard' AND NOT g.rolsuper AND NOT g.rolbypassrls AND NOT g.rolcanlogin AND NOT g.rolcreaterole AND NOT g.rolcreatedb AND NOT g.rolinherit) AND EXISTS(SELECT 1 FROM pg_catalog.pg_proc p WHERE p.oid='public.managed_payment_account_capture(text,uuid,uuid,uuid)'::regprocedure AND p.prosecdef AND p.proowner='payment_closure_guard'::regrole AND p.proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp']) AS allowed FROM pg_catalog.pg_roles r WHERE r.rolname=current_user
$repository$;

CREATE OR REPLACE FUNCTION public.payment_account_repository_runtime(valid_until bigint) RETURNS boolean
 LANGUAGE sql VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
 WITH protected(name) AS (VALUES ('managed_payment_operation'),('managed_payment_action'),('managed_payment_effect'), ('managed_payment_projection'),('managed_payment_history'),('managed_payment_outbox'),('managed_payment_account_gate'), ('managed_payment_account_fence'),('managed_payment_account_action'),('managed_payment_account_history'),('managed_payment_account_outbox'), ('managed_payment_account_observation'),('managed_payment_account_observation_member'),('managed_payment_account_observation_reference'),('managed_payment_monetary_proof')), relations AS (SELECT c.* FROM protected p JOIN pg_catalog.pg_class c ON c.relname=p.name JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public') SELECT NOT r.rolsuper AND NOT r.rolbypassrls AND NOT pg_catalog.has_database_privilege(current_user,current_database(),'CREATE') AND NOT pg_catalog.has_database_privilege(current_user,current_database(),'TEMP') AND NOT r.rolcreaterole AND NOT r.rolcreatedb AND NOT has_schema_privilege(current_user,'public','CREATE') AND NOT pg_has_role(current_user,'payment_closure_guard','MEMBER') AND (SELECT count(*)=15 AND bool_and(relrowsecurity AND relforcerowsecurity AND NOT pg_has_role(current_user,relowner,'MEMBER')) FROM relations) AND EXISTS(SELECT 1 FROM pg_catalog.pg_roles g WHERE g.rolname='payment_closure_guard' AND NOT g.rolsuper AND NOT g.rolbypassrls AND NOT g.rolcanlogin AND NOT g.rolcreaterole AND NOT g.rolcreatedb AND NOT g.rolinherit) AND EXISTS(SELECT 1 FROM pg_catalog.pg_proc p WHERE p.oid='public.managed_payment_account_capture(text,uuid,uuid,uuid)'::regprocedure AND p.prosecdef AND p.proowner='payment_closure_guard'::regrole AND p.proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp']) AND $1>floor(extract(epoch FROM clock_timestamp())*1000)::bigint AS allowed FROM pg_catalog.pg_roles r WHERE r.rolname=current_user
$repository$;
