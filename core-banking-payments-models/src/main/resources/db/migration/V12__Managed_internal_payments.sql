-- Managed internal instructions do not backfill legacy rail orders or infer balances.
CREATE TABLE managed_payment_operation (
 tenant_id VARCHAR(64) NOT NULL CHECK(tenant_id ~ '^[A-Za-z0-9_-]{1,64}$'),
 payment_id UUID NOT NULL,
 source_account_id UUID NOT NULL,
 destination_account_id UUID NOT NULL CHECK(destination_account_id<>source_account_id),
 create_action_id UUID NOT NULL,
 request_digest VARCHAR(64) NOT NULL CHECK(request_digest ~ '^[a-f0-9]{64}$'),
 payload JSONB NOT NULL,
 state VARCHAR(32) NOT NULL CHECK(state IN('RECEIVED','RESERVING','RESERVED','COMMITTING','CANCELLING','COMPLETED','REVERSED','CANCELLED','EXPIRED','RECOVERY_REQUIRED')),
 version BIGINT NOT NULL CHECK(version>=0),
 CHECK(jsonb_typeof(payload)='object' AND payload->>'paymentId' IS NOT NULL AND payload->>'createActionId' IS NOT NULL
  AND payload->>'tenant' IS NOT NULL AND payload->>'sourceAccountId' IS NOT NULL AND payload->>'destinationAccountId' IS NOT NULL
  AND payload->>'amount' IS NOT NULL AND payload->>'currency' IS NOT NULL AND payload->>'currencyScale' IS NOT NULL
  AND payload->>'currencyPolicy' IS NOT NULL AND length(payload->>'currencyPolicy') BETWEEN 1 AND 100
  AND payload->>'ttlSeconds' IS NOT NULL AND payload->>'notAfterEpochMillis' IS NOT NULL
  AND payload->>'initiator' IS NOT NULL AND length(payload->>'initiator') BETWEEN 1 AND 255
  AND payload->>'state' IS NOT NULL AND payload->>'version' IS NOT NULL),
 CHECK((payload->>'amount')::numeric=trunc((payload->>'amount')::numeric,(payload->>'currencyScale')::int)),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,payment_id),
 UNIQUE(tenant_id,payment_id,source_account_id,destination_account_id),
 CHECK(payload->>'tenant'=tenant_id AND (payload->>'paymentId')::uuid=payment_id
  AND (payload->>'createActionId')::uuid=create_action_id
  AND (payload->>'sourceAccountId')::uuid=source_account_id
  AND (payload->>'destinationAccountId')::uuid=destination_account_id
  AND payload->>'state'=state AND (payload->>'version')::bigint=version),
 CHECK((payload->>'currency') ~ '^[A-Z]{3}$' AND (payload->>'currencyScale')::int BETWEEN 0 AND 18
  AND (payload->>'amount')::numeric>0 AND (payload->>'amount')::numeric<1e20
  AND (payload->>'ttlSeconds')::int BETWEEN 1 AND 900 AND (payload->>'notAfterEpochMillis')::bigint>0),
 CHECK(state NOT IN('COMPLETED','REVERSED') OR (payload->>'postingId')::uuid IS NOT NULL),
 CHECK(state<>'REVERSED' OR (payload->>'reversalId')::uuid IS NOT NULL),
 CHECK(state NOT IN('RESERVED','COMMITTING','CANCELLING','COMPLETED','REVERSED','CANCELLED') OR (payload->>'reservationId')::uuid IS NOT NULL),
 CHECK(state<>'EXPIRED' OR (payload->>'reservationId')::uuid IS NOT NULL OR (payload->>'absenceReceiptId')::uuid IS NOT NULL)
);
CREATE TABLE managed_payment_action (
 tenant_id VARCHAR(64) NOT NULL,payment_id UUID NOT NULL,source_account_id UUID NOT NULL,destination_account_id UUID NOT NULL,
 action_id UUID NOT NULL,kind VARCHAR(16) NOT NULL CHECK(kind IN('CREATE','CONFIRM','CANCEL','RECOVER','CLAIM')),
 intent_digest VARCHAR(64) NOT NULL CHECK(intent_digest ~ '^[a-f0-9]{64}$'),
 actor_subject VARCHAR(255) NOT NULL,receipt JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,action_id),
 FOREIGN KEY(tenant_id,payment_id,source_account_id,destination_account_id) REFERENCES managed_payment_operation(tenant_id,payment_id,source_account_id,destination_account_id)
);
CREATE TABLE managed_payment_effect (
 tenant_id VARCHAR(64) NOT NULL,payment_id UUID NOT NULL,source_account_id UUID NOT NULL,destination_account_id UUID NOT NULL,
 effect_id UUID NOT NULL,participant_action_id UUID NOT NULL,kind VARCHAR(16) NOT NULL CHECK(kind IN('RESERVE','COMMIT','RELEASE','OBSERVE')),
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN('PENDING','CLAIMED','ACKNOWLEDGED','EXHAUSTED')),
 fence BIGINT NOT NULL DEFAULT 0 CHECK(fence>=0),attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
 owner_subject VARCHAR(255),lease_until TIMESTAMPTZ,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,effect_id),UNIQUE(tenant_id,payment_id,kind),UNIQUE(tenant_id,participant_action_id,kind),
 FOREIGN KEY(tenant_id,payment_id,source_account_id,destination_account_id) REFERENCES managed_payment_operation(tenant_id,payment_id,source_account_id,destination_account_id),
 CHECK((state='CLAIMED')=(owner_subject IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE TABLE managed_payment_projection (
 tenant_id VARCHAR(64) NOT NULL,payment_id UUID NOT NULL,source_account_id UUID NOT NULL,destination_account_id UUID NOT NULL,payload JSONB NOT NULL,
 PRIMARY KEY(tenant_id,payment_id),
 FOREIGN KEY(tenant_id,payment_id,source_account_id,destination_account_id) REFERENCES managed_payment_operation(tenant_id,payment_id,source_account_id,destination_account_id)
);
CREATE TABLE managed_payment_history (
 tenant_id VARCHAR(64) NOT NULL,payment_id UUID NOT NULL,source_account_id UUID NOT NULL,destination_account_id UUID NOT NULL,version BIGINT NOT NULL CHECK(version>=0),payload JSONB NOT NULL,
 PRIMARY KEY(tenant_id,payment_id,version),
 FOREIGN KEY(tenant_id,payment_id,source_account_id,destination_account_id) REFERENCES managed_payment_operation(tenant_id,payment_id,source_account_id,destination_account_id)
);
CREATE TABLE managed_payment_outbox (
 tenant_id VARCHAR(64) NOT NULL,payment_id UUID NOT NULL,source_account_id UUID NOT NULL,destination_account_id UUID NOT NULL,version BIGINT NOT NULL CHECK(version>=0),event_id UUID NOT NULL,payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),delivered_at TIMESTAMPTZ,
 PRIMARY KEY(tenant_id,payment_id,version),UNIQUE(event_id),
 FOREIGN KEY(tenant_id,payment_id,source_account_id,destination_account_id) REFERENCES managed_payment_operation(tenant_id,payment_id,source_account_id,destination_account_id)
);
CREATE FUNCTION public.managed_payment_scope(tenant text,source uuid,destination uuid) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT tenant=current_setting('firefly.payment.tenant',true)
 AND coalesce(nullif(current_setting('firefly.payment.accounts',true),''),'[]')::jsonb ? source::text
 AND coalesce(nullif(current_setting('firefly.payment.accounts',true),''),'[]')::jsonb ? destination::text
$$;
-- This predicate is an invoker function: it reads only transaction context, never private rows.
REVOKE ALL ON FUNCTION public.managed_payment_scope(text,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.managed_payment_scope(text,uuid,uuid) TO PUBLIC;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['managed_payment_operation','managed_payment_action','managed_payment_effect','managed_payment_projection','managed_payment_history','managed_payment_outbox'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY managed_read ON public.%I FOR SELECT USING(public.managed_payment_scope(tenant_id,source_account_id,destination_account_id))',tab);
  EXECUTE format('CREATE POLICY managed_insert ON public.%I FOR INSERT WITH CHECK(public.managed_payment_scope(tenant_id,source_account_id,destination_account_id) AND current_setting(''firefly.payment.purpose'',true) IN(''CREATE'',''MUTATE'',''OBSERVE''))',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['managed_payment_operation','managed_payment_effect','managed_payment_projection'] LOOP
  EXECUTE format('CREATE POLICY managed_update ON public.%I FOR UPDATE USING(public.managed_payment_scope(tenant_id,source_account_id,destination_account_id)) WITH CHECK(public.managed_payment_scope(tenant_id,source_account_id,destination_account_id) AND current_setting(''firefly.payment.purpose'',true) IN(''MUTATE'',''OBSERVE''))',tab);
 END LOOP;
END $$;
CREATE FUNCTION public.guard_managed_payment_operation() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'MANAGED_PAYMENT_HISTORY_REQUIRED'; END IF;
 IF TG_OP='UPDATE' THEN
  IF (NEW.tenant_id,NEW.payment_id,NEW.source_account_id,NEW.destination_account_id,NEW.create_action_id,NEW.request_digest,NEW.created_at)
     IS DISTINCT FROM (OLD.tenant_id,OLD.payment_id,OLD.source_account_id,OLD.destination_account_id,OLD.create_action_id,OLD.request_digest,OLD.created_at)
   OR (NEW.payload-ARRAY['state','version','reservationId','postingId','reversalId','absenceReceiptId','ledgerVersion','observedAt','pendingEffectId','recoveryCode','ledgerState','reservationExpiresAt'])
     IS DISTINCT FROM (OLD.payload-ARRAY['state','version','reservationId','postingId','reversalId','absenceReceiptId','ledgerVersion','observedAt','pendingEffectId','recoveryCode','ledgerState','reservationExpiresAt'])
   OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'IMMUTABLE_PAYMENT_INTENT_OR_VERSION'; END IF;
 END IF;
 IF current_setting('firefly.payment.purpose',true) IS DISTINCT FROM 'OBSERVE' AND (
  (NEW.state IN('RESERVED','COMPLETED','REVERSED','CANCELLED','EXPIRED') AND (TG_OP='INSERT' OR NEW.state IS DISTINCT FROM OLD.state))
  OR (TG_OP='UPDATE' AND ROW(NEW.payload->'reservationId',NEW.payload->'postingId',NEW.payload->'reversalId',NEW.payload->'absenceReceiptId',NEW.payload->'ledgerVersion',NEW.payload->'ledgerState',NEW.payload->'observedAt',NEW.payload->'reservationExpiresAt')
   IS DISTINCT FROM ROW(OLD.payload->'reservationId',OLD.payload->'postingId',OLD.payload->'reversalId',OLD.payload->'absenceReceiptId',OLD.payload->'ledgerVersion',OLD.payload->'ledgerState',OLD.payload->'observedAt',OLD.payload->'reservationExpiresAt'))
 ) THEN RAISE EXCEPTION 'VERIFIED_LEDGER_OBSERVATION_REQUIRED'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER managed_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON managed_payment_operation FOR EACH ROW EXECUTE FUNCTION public.guard_managed_payment_operation();
CREATE FUNCTION public.require_complete_managed_payment() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.managed_payment_action WHERE tenant_id=NEW.tenant_id AND payment_id=NEW.payment_id AND action_id=NEW.create_action_id AND kind='CREATE')
  OR NOT EXISTS(SELECT 1 FROM public.managed_payment_effect WHERE tenant_id=NEW.tenant_id AND payment_id=NEW.payment_id AND kind='RESERVE')
  OR NOT EXISTS(SELECT 1 FROM public.managed_payment_projection WHERE tenant_id=NEW.tenant_id AND payment_id=NEW.payment_id AND payload=NEW.payload)
  OR NOT EXISTS(SELECT 1 FROM public.managed_payment_history WHERE tenant_id=NEW.tenant_id AND payment_id=NEW.payment_id AND version=NEW.version)
  OR NOT EXISTS(SELECT 1 FROM public.managed_payment_outbox WHERE tenant_id=NEW.tenant_id AND payment_id=NEW.payment_id AND version=NEW.version)
 THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_RECORDS_REQUIRED'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER managed_operation_complete AFTER INSERT OR UPDATE ON managed_payment_operation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_managed_payment();
CREATE INDEX managed_payment_recovery_due ON managed_payment_effect(next_attempt_at,payment_id) WHERE state IN('PENDING','CLAIMED');

CREATE FUNCTION public.guard_managed_payment_effect() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' OR (NEW.tenant_id,NEW.payment_id,NEW.source_account_id,NEW.destination_account_id,NEW.effect_id,NEW.participant_action_id,NEW.kind)
  IS DISTINCT FROM (OLD.tenant_id,OLD.payment_id,OLD.source_account_id,OLD.destination_account_id,OLD.effect_id,OLD.participant_action_id,OLD.kind)
  OR NEW.fence<OLD.fence OR (CASE WHEN NEW.fence=OLD.fence THEN false WHEN OLD.fence=9223372036854775807 THEN true ELSE NEW.fence<>OLD.fence+1 END) OR NEW.attempts<OLD.attempts
  OR (NEW.fence=OLD.fence AND NEW.attempts<>OLD.attempts)
  OR (NEW.fence>OLD.fence AND (NEW.state<>'CLAIMED' OR NEW.attempts<>OLD.attempts+1))
  OR (OLD.state='ACKNOWLEDGED' AND OLD.kind<>'OBSERVE' AND NEW IS DISTINCT FROM OLD)
 THEN RAISE EXCEPTION 'IMMUTABLE_PAYMENT_EFFECT'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER managed_effect_guard BEFORE UPDATE OR DELETE ON managed_payment_effect FOR EACH ROW EXECUTE FUNCTION public.guard_managed_payment_effect();
