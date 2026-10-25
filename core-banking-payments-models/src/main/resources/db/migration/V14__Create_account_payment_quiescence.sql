-- Payment-owner preparation is not business account closure.
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payment_closure_guard') THEN
  CREATE ROLE payment_closure_guard NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB NOINHERIT;
 END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payment_closure_guard' AND
   (rolcanlogin OR rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolinherit))
 THEN RAISE EXCEPTION 'NONPRIVILEGED_PAYMENT_CLOSURE_GUARD_REQUIRED'; END IF;
END $$;

CREATE TABLE managed_payment_account_gate (
 tenant_id varchar(64) NOT NULL CHECK(tenant_id ~ '^[A-Za-z0-9_-]{1,64}$'),account_id uuid NOT NULL,
 source_revision bigint NOT NULL DEFAULT 0 CHECK(source_revision>=0),
 fence_version bigint NOT NULL DEFAULT 0 CHECK(fence_version>=0),active_fence_id uuid,
 PRIMARY KEY(tenant_id,account_id)
);
CREATE TABLE managed_payment_account_fence (
 tenant_id varchar(64) NOT NULL,account_id uuid NOT NULL,fence_id uuid NOT NULL,closure_id uuid NOT NULL,
 selection jsonb NOT NULL,request jsonb NOT NULL,ledger_version bigint NOT NULL CHECK(ledger_version>=0),
 state varchar(16) NOT NULL CHECK(state IN('FENCED','RELEASED')),version bigint NOT NULL CHECK(version>0),
 source_revision bigint NOT NULL CHECK(source_revision>=0),last_action_id uuid NOT NULL,acceptance jsonb NOT NULL,
 PRIMARY KEY(tenant_id,account_id,fence_id),UNIQUE(tenant_id,account_id,closure_id),
 UNIQUE(tenant_id,account_id,version) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,account_id) REFERENCES managed_payment_account_gate(tenant_id,account_id),
 CHECK(selection->>'tenantId'=tenant_id AND (selection->>'accountId')::uuid=account_id
  AND selection->>'enrollmentId' IS NOT NULL AND selection->>'contractId' IS NOT NULL
  AND selection->>'currency' ~ '^[A-Z]{3}$' AND (selection->>'currencyScale')::int BETWEEN 0 AND 18
  AND length(selection->>'currencyPolicy') BETWEEN 1 AND 100),
 CHECK((acceptance->>'fenceId')::uuid=fence_id AND (acceptance->>'closureId')::uuid=closure_id
  AND acceptance->'selection'=selection)
);
ALTER TABLE managed_payment_account_gate ADD CONSTRAINT active_fence_reference
 FOREIGN KEY(tenant_id,account_id,active_fence_id) REFERENCES managed_payment_account_fence(tenant_id,account_id,fence_id)
 DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE managed_payment_account_action (
 tenant_id varchar(64) NOT NULL,account_id uuid NOT NULL,action_id uuid NOT NULL,fence_id uuid NOT NULL,
 kind varchar(16) NOT NULL CHECK(kind IN('QUIESCE','RELEASE')),intent_digest varchar(64) NOT NULL CHECK(intent_digest ~ '^[a-f0-9]{64}$'),
 selection jsonb NOT NULL,request jsonb NOT NULL,receipt jsonb NOT NULL,
 actor_issuer text NOT NULL,actor_subject varchar(255) NOT NULL,
 PRIMARY KEY(tenant_id,action_id),
 FOREIGN KEY(tenant_id,account_id,fence_id) REFERENCES managed_payment_account_fence(tenant_id,account_id,fence_id)
);
CREATE TABLE managed_payment_account_history (
 tenant_id varchar(64) NOT NULL,account_id uuid NOT NULL,fence_id uuid NOT NULL,version bigint NOT NULL,
 action_id uuid NOT NULL,payload jsonb NOT NULL,PRIMARY KEY(tenant_id,account_id,fence_id,version),
 FOREIGN KEY(tenant_id,account_id,fence_id) REFERENCES managed_payment_account_fence(tenant_id,account_id,fence_id),
 FOREIGN KEY(tenant_id,action_id) REFERENCES managed_payment_account_action(tenant_id,action_id)
);
CREATE TABLE managed_payment_account_outbox (
 tenant_id varchar(64) NOT NULL,account_id uuid NOT NULL,fence_id uuid NOT NULL,version bigint NOT NULL,
 action_id uuid NOT NULL,event_id uuid NOT NULL UNIQUE,payload jsonb NOT NULL,
 PRIMARY KEY(tenant_id,account_id,fence_id,version),
 FOREIGN KEY(tenant_id,account_id,fence_id) REFERENCES managed_payment_account_fence(tenant_id,account_id,fence_id),
 FOREIGN KEY(tenant_id,action_id) REFERENCES managed_payment_account_action(tenant_id,action_id)
);
CREATE FUNCTION public.payment_closure_scope(tenant text,account uuid) RETURNS boolean
 LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT tenant=current_setting('firefly.payment_closure.tenant',true)
 AND account::text=current_setting('firefly.payment_closure.account',true)
 AND current_setting('firefly.payment_closure.purpose',true) IN('quiesce','read','observe','release')
 AND current_setting('firefly.payment_closure.xid',true)=pg_current_xact_id_if_assigned()::text
 AND coalesce(nullif(current_setting('firefly.payment_closure.until',true),''),'0')::bigint>
   floor(extract(epoch FROM clock_timestamp())*1000)::bigint
 AND length(coalesce(current_setting('firefly.payment_closure.actor_subject',true),'')) BETWEEN 1 AND 255
 AND length(coalesce(current_setting('firefly.payment_closure.actor_issuer',true),''))>0
$$;
CREATE FUNCTION public.payment_account_gate_scope(tenant text,account uuid) RETURNS boolean
 LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT public.payment_closure_scope(tenant,account) OR (
  tenant=current_setting('firefly.payment.tenant',true)
  AND coalesce(nullif(current_setting('firefly.payment.accounts',true),''),'[]')::jsonb ? account::text
  AND current_setting('firefly.payment.purpose',true) IN('CREATE','MUTATE','OBSERVE')
  AND current_setting('firefly.payment.xid',true)=pg_current_xact_id_if_assigned()::text
  AND coalesce(nullif(current_setting('firefly.payment.until',true),''),'0')::bigint>
    floor(extract(epoch FROM clock_timestamp())*1000)::bigint)
$$;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['managed_payment_account_gate','managed_payment_account_fence','managed_payment_account_action','managed_payment_account_history','managed_payment_account_outbox'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',tab);
  IF tab='managed_payment_account_gate' THEN
   EXECUTE format('CREATE POLICY account_gate_context ON public.%I USING(public.payment_account_gate_scope(tenant_id,account_id)) WITH CHECK(public.payment_account_gate_scope(tenant_id,account_id))',tab);
  ELSE
   EXECUTE format('CREATE POLICY closure_context ON public.%I FOR SELECT USING(public.payment_closure_scope(tenant_id,account_id))',tab);
   EXECUTE format('CREATE POLICY closure_write ON public.%I FOR INSERT WITH CHECK(public.payment_closure_scope(tenant_id,account_id) AND current_setting(''firefly.payment_closure.purpose'',true) IN(''quiesce'',''release''))',tab);
  END IF;
 END LOOP;
END $$;
CREATE POLICY closure_fence_update ON managed_payment_account_fence FOR UPDATE
 USING(public.payment_closure_scope(tenant_id,account_id))
 WITH CHECK(public.payment_closure_scope(tenant_id,account_id) AND current_setting('firefly.payment_closure.purpose',true)='release');

CREATE FUNCTION public.managed_payment_account_gate(tenant text,account uuid) RETURNS void
 LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$ BEGIN
 IF public.payment_account_gate_scope(tenant,account) IS NOT TRUE THEN RAISE EXCEPTION 'CURRENT_ACCOUNT_GATE_CONTEXT_REQUIRED'; END IF;
 INSERT INTO public.managed_payment_account_gate(tenant_id,account_id) VALUES(tenant,account) ON CONFLICT DO NOTHING;
 PERFORM 1 FROM public.managed_payment_account_gate WHERE tenant_id=tenant AND account_id=account FOR UPDATE;
 IF NOT FOUND OR public.payment_account_gate_scope(tenant,account) IS NOT TRUE THEN RAISE EXCEPTION 'CURRENT_ACCOUNT_GATE_CONTEXT_REQUIRED'; END IF;
END $$;
CREATE FUNCTION public.guard_payment_account_append_only() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN RAISE EXCEPTION 'RETAINED_PAYMENT_CLOSURE_EVIDENCE_REQUIRED'; END $$;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['managed_payment_account_action','managed_payment_account_history','managed_payment_account_outbox'] LOOP
  EXECUTE format('CREATE TRIGGER retained_closure_evidence BEFORE UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.guard_payment_account_append_only()',tab);
 END LOOP;
END $$;
CREATE FUNCTION public.guard_payment_account_fence() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.state IS DISTINCT FROM 'FENCED'
   OR NEW.version IS DISTINCT FROM (NEW.acceptance->>'fenceVersion')::bigint
   OR NEW.last_action_id IS DISTINCT FROM (NEW.acceptance->>'actionId')::uuid
   OR current_setting('firefly.payment_closure.purpose',true) IS DISTINCT FROM 'quiesce'
  THEN RAISE EXCEPTION 'INITIAL_PAYMENT_ACCOUNT_FENCE_REQUIRED'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' OR (NEW.tenant_id,NEW.account_id,NEW.fence_id,NEW.closure_id,NEW.selection,NEW.request,NEW.ledger_version,NEW.source_revision,NEW.acceptance)
  IS DISTINCT FROM (OLD.tenant_id,OLD.account_id,OLD.fence_id,OLD.closure_id,OLD.selection,OLD.request,OLD.ledger_version,OLD.source_revision,OLD.acceptance)
  OR OLD.state<>'FENCED' OR NEW.state<>'RELEASED' OR NEW.version<>OLD.version+1
 THEN RAISE EXCEPTION 'IMMUTABLE_PAYMENT_ACCOUNT_FENCE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_account_fence BEFORE INSERT OR UPDATE OR DELETE ON managed_payment_account_fence FOR EACH ROW EXECUTE FUNCTION public.guard_payment_account_fence();
-- Validate final rows, preserving legitimate multi-statement release/re-fence ordering.
CREATE FUNCTION public.require_complete_payment_account_fence() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE gate public.managed_payment_account_gate; fence public.managed_payment_account_fence;
 q public.managed_payment_account_action; r public.managed_payment_account_action;
 accepted_version bigint; accepted_action uuid; expected_count int;
BEGIN
 SELECT * INTO gate FROM public.managed_payment_account_gate WHERE tenant_id=NEW.tenant_id AND account_id=NEW.account_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_CLOSURE_GATE_REQUIRED'; END IF;
 IF TG_TABLE_NAME='managed_payment_account_gate' AND gate.fence_version=0 THEN
  IF gate.active_fence_id IS NOT NULL OR public.payment_account_gate_scope(gate.tenant_id,gate.account_id) IS NOT TRUE
   OR EXISTS(SELECT 1 FROM public.managed_payment_account_fence WHERE tenant_id=gate.tenant_id AND account_id=gate.account_id)
  THEN RAISE EXCEPTION 'EMPTY_PAYMENT_CLOSURE_GATE_REQUIRED'; END IF;
  RETURN NULL;
 END IF;
 -- Require scope at commit so invoker RLS cannot hide incomplete evidence.
 IF public.payment_closure_scope(gate.tenant_id,gate.account_id) IS NOT TRUE OR gate.fence_version<=0
 THEN RAISE EXCEPTION 'CURRENT_PAYMENT_CLOSURE_EVIDENCE_SCOPE_REQUIRED'; END IF;
 IF EXISTS(SELECT 1 FROM public.managed_payment_account_fence WHERE tenant_id=gate.tenant_id AND account_id=gate.account_id AND version>gate.fence_version)
 THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_CLOSURE_GATE_REQUIRED'; END IF;
 IF gate.active_fence_id IS NULL THEN
  IF NOT EXISTS(SELECT 1 FROM public.managed_payment_account_fence WHERE tenant_id=gate.tenant_id AND account_id=gate.account_id AND version=gate.fence_version AND state='RELEASED')
   OR EXISTS(SELECT 1 FROM public.managed_payment_account_fence WHERE tenant_id=gate.tenant_id AND account_id=gate.account_id AND state='FENCED')
  THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_CLOSURE_GATE_REQUIRED'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM public.managed_payment_account_fence WHERE tenant_id=gate.tenant_id AND account_id=gate.account_id AND fence_id=gate.active_fence_id AND version=gate.fence_version AND state='FENCED')
   OR EXISTS(SELECT 1 FROM public.managed_payment_account_fence WHERE tenant_id=gate.tenant_id AND account_id=gate.account_id AND fence_id<>gate.active_fence_id AND state='FENCED')
  THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_CLOSURE_GATE_REQUIRED'; END IF;
 END IF;
 FOR fence IN SELECT * FROM public.managed_payment_account_fence WHERE tenant_id=gate.tenant_id AND account_id=gate.account_id LOOP
  accepted_version=(fence.acceptance->>'fenceVersion')::bigint;
  accepted_action=(fence.acceptance->>'actionId')::uuid;
  IF accepted_version IS NULL OR accepted_version<=0 OR accepted_action IS NULL
   OR (fence.acceptance->>'fenceId')::uuid IS DISTINCT FROM fence.fence_id
   OR (fence.acceptance->>'closureId')::uuid IS DISTINCT FROM fence.closure_id
   OR fence.acceptance->'selection' IS DISTINCT FROM fence.selection
   OR (fence.acceptance->>'observedLedgerVersion')::bigint IS DISTINCT FROM fence.ledger_version
   OR (fence.acceptance->>'observedSourceVersion')::bigint IS DISTINCT FROM fence.source_revision
   OR fence.acceptance->>'acceptedAt' IS NULL
  THEN RAISE EXCEPTION 'EXACT_PAYMENT_QUIESCENCE_EVIDENCE_REQUIRED'; END IF;
  SELECT * INTO q FROM public.managed_payment_account_action WHERE tenant_id=fence.tenant_id AND account_id=fence.account_id AND fence_id=fence.fence_id AND action_id=accepted_action AND kind='QUIESCE';
  IF NOT FOUND OR q.selection IS DISTINCT FROM fence.selection OR q.request IS DISTINCT FROM fence.request OR q.receipt IS DISTINCT FROM fence.acceptance
   OR length(q.actor_subject)=0 OR length(q.actor_issuer)=0
   OR fence.request IS DISTINCT FROM jsonb_build_object('actionId',accepted_action,'closureId',fence.closure_id,'enrollmentId',(fence.selection->>'enrollmentId')::uuid,'expectedFenceVersion',accepted_version-1)
   OR NOT EXISTS(SELECT 1 FROM public.managed_payment_account_history WHERE tenant_id=fence.tenant_id AND account_id=fence.account_id AND fence_id=fence.fence_id AND version=accepted_version AND action_id=accepted_action AND payload=fence.acceptance)
   OR NOT EXISTS(SELECT 1 FROM public.managed_payment_account_outbox WHERE tenant_id=fence.tenant_id AND account_id=fence.account_id AND fence_id=fence.fence_id AND version=accepted_version AND action_id=accepted_action AND payload=fence.acceptance)
  THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_QUIESCENCE_EVIDENCE_REQUIRED'; END IF;
  IF accepted_version>1 AND NOT EXISTS(SELECT 1 FROM public.managed_payment_account_fence previous WHERE previous.tenant_id=fence.tenant_id AND previous.account_id=fence.account_id AND previous.fence_id<>fence.fence_id AND previous.state='RELEASED' AND previous.version=accepted_version-1)
  THEN RAISE EXCEPTION 'CONTIGUOUS_PAYMENT_FENCE_HISTORY_REQUIRED'; END IF;
  IF fence.state='FENCED' THEN
   expected_count=1;
   IF fence.version<>accepted_version OR fence.last_action_id<>accepted_action
   THEN RAISE EXCEPTION 'EXACT_PAYMENT_QUIESCENCE_EVIDENCE_REQUIRED'; END IF;
  ELSE
   expected_count=2;
   IF accepted_version=9223372036854775807 OR fence.version<>accepted_version+1
   THEN RAISE EXCEPTION 'EXACT_PAYMENT_RELEASE_EVIDENCE_REQUIRED'; END IF;
   SELECT * INTO r FROM public.managed_payment_account_action WHERE tenant_id=fence.tenant_id AND account_id=fence.account_id AND fence_id=fence.fence_id AND action_id=fence.last_action_id AND kind='RELEASE';
   IF NOT FOUND OR r.selection IS DISTINCT FROM fence.selection OR length(r.actor_subject)=0 OR length(r.actor_issuer)=0
    OR r.request IS DISTINCT FROM jsonb_build_object('actionId',fence.last_action_id,'closureId',fence.closure_id,'expectedFenceVersion',accepted_version)
    OR (r.receipt->>'fenceId')::uuid IS DISTINCT FROM fence.fence_id
    OR (r.receipt->>'actionId')::uuid IS DISTINCT FROM fence.last_action_id
    OR (r.receipt->>'closureId')::uuid IS DISTINCT FROM fence.closure_id
    OR r.receipt->'selection' IS DISTINCT FROM fence.selection
    OR (r.receipt->>'fenceVersion')::bigint IS DISTINCT FROM fence.version OR r.receipt->>'releasedAt' IS NULL
    OR NOT EXISTS(SELECT 1 FROM public.managed_payment_account_history WHERE tenant_id=fence.tenant_id AND account_id=fence.account_id AND fence_id=fence.fence_id AND version=fence.version AND action_id=fence.last_action_id AND payload=r.receipt)
    OR NOT EXISTS(SELECT 1 FROM public.managed_payment_account_outbox WHERE tenant_id=fence.tenant_id AND account_id=fence.account_id AND fence_id=fence.fence_id AND version=fence.version AND action_id=fence.last_action_id AND payload=r.receipt)
   THEN RAISE EXCEPTION 'ATOMIC_PAYMENT_RELEASE_EVIDENCE_REQUIRED'; END IF;
  END IF;
  IF (SELECT count(*) FROM public.managed_payment_account_action WHERE tenant_id=fence.tenant_id AND account_id=fence.account_id AND fence_id=fence.fence_id)<>expected_count
   OR (SELECT count(*) FROM public.managed_payment_account_history WHERE tenant_id=fence.tenant_id AND account_id=fence.account_id AND fence_id=fence.fence_id)<>expected_count
   OR (SELECT count(*) FROM public.managed_payment_account_outbox WHERE tenant_id=fence.tenant_id AND account_id=fence.account_id AND fence_id=fence.fence_id)<>expected_count
  THEN RAISE EXCEPTION 'EXACT_PAYMENT_CLOSURE_JOURNAL_REQUIRED'; END IF;
 END LOOP;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER complete_account_fence AFTER INSERT OR UPDATE ON managed_payment_account_fence
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_payment_account_fence();
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['managed_payment_account_action','managed_payment_account_history','managed_payment_account_outbox'] LOOP
  EXECUTE format('CREATE CONSTRAINT TRIGGER complete_account_journal AFTER INSERT ON public.%I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_payment_account_fence()',tab);
 END LOOP;
END $$;

CREATE FUNCTION public.managed_payment_account_quiesce(tenant text,account uuid,action uuid,closure uuid,enrollment uuid,expected bigint,selected jsonb,ledger_revision bigint,digest text)
 RETURNS jsonb LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE gate public.managed_payment_account_gate; prior public.managed_payment_account_action; id uuid; receipt jsonb; intent jsonb;
BEGIN
 IF public.payment_closure_scope(tenant,account) IS NOT TRUE OR current_setting('firefly.payment_closure.purpose',true)<>'quiesce'
 THEN RAISE EXCEPTION 'CURRENT_PAYMENT_QUIESCENCE_AUTHORITY_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('payment-account-closure-action:'||tenant||':'||action,0));
 PERFORM public.managed_payment_account_gate(tenant,account);
 intent=jsonb_build_object('actionId',action,'closureId',closure,'enrollmentId',enrollment,'expectedFenceVersion',expected);
 SELECT * INTO prior FROM public.managed_payment_account_action WHERE tenant_id=tenant AND action_id=action;
 IF FOUND THEN
  IF prior.account_id<>account OR prior.kind<>'QUIESCE' OR prior.selection IS DISTINCT FROM selected OR prior.request IS DISTINCT FROM intent OR prior.intent_digest<>digest
  THEN RAISE EXCEPTION 'PAYMENT_CLOSURE_ACTION_INTENT_CONFLICT'; END IF;
  RETURN prior.receipt;
 END IF;
 IF selected->>'tenantId' IS DISTINCT FROM tenant OR selected->>'accountId' IS DISTINCT FROM account::text
  OR selected->>'enrollmentId' IS DISTINCT FROM enrollment::text OR ledger_revision<0 OR expected<0
 THEN RAISE EXCEPTION 'EXACT_LEDGER_CLOSURE_SELECTION_REQUIRED'; END IF;
 SELECT * INTO gate FROM public.managed_payment_account_gate WHERE tenant_id=tenant AND account_id=account;
 IF gate.fence_version<>expected OR gate.active_fence_id IS NOT NULL OR expected=9223372036854775807
 THEN RAISE EXCEPTION 'PAYMENT_ACCOUNT_FENCE_VERSION_CONFLICT'; END IF;
 id=gen_random_uuid();
 receipt=jsonb_build_object('fenceId',id,'actionId',action,'closureId',closure,'selection',selected,'fenceVersion',expected+1,
  'observedSourceVersion',gate.source_revision,'observedLedgerVersion',ledger_revision,'acceptedAt',clock_timestamp());
 INSERT INTO public.managed_payment_account_fence VALUES(tenant,account,id,closure,selected,intent,ledger_revision,'FENCED',expected+1,gate.source_revision,action,receipt);
 INSERT INTO public.managed_payment_account_action VALUES(tenant,account,action,id,'QUIESCE',digest,selected,intent,receipt,current_setting('firefly.payment_closure.actor_issuer'),current_setting('firefly.payment_closure.actor_subject'));
 INSERT INTO public.managed_payment_account_history VALUES(tenant,account,id,expected+1,action,receipt);
 INSERT INTO public.managed_payment_account_outbox VALUES(tenant,account,id,expected+1,action,gen_random_uuid(),receipt);
 UPDATE public.managed_payment_account_gate SET active_fence_id=id,fence_version=expected+1 WHERE tenant_id=tenant AND account_id=account;
 RETURN receipt;
END $$;

-- Existing admitted work may drain; terminal work cannot be reactivated after clearance.
INSERT INTO managed_payment_account_gate(tenant_id,account_id,source_revision)
 SELECT tenant_id,account_id,count(*) FROM (
  SELECT tenant_id,source_account_id AS account_id FROM managed_payment_operation
  UNION ALL SELECT tenant_id,destination_account_id FROM managed_payment_operation) admitted GROUP BY tenant_id,account_id;
CREATE FUNCTION public.guard_managed_payment_account_work() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE account uuid; fenced boolean; parent_state text; added boolean;
BEGIN
 FOR account IN SELECT id FROM (VALUES(NEW.source_account_id),(NEW.destination_account_id)) pair(id) ORDER BY id LOOP
  PERFORM public.managed_payment_account_gate(NEW.tenant_id,account);
 END LOOP;
 SELECT bool_or(active_fence_id IS NOT NULL) INTO fenced FROM public.managed_payment_account_gate
  WHERE tenant_id=NEW.tenant_id AND account_id IN(NEW.source_account_id,NEW.destination_account_id);
 IF TG_TABLE_NAME='managed_payment_operation' THEN
  IF TG_OP='INSERT' THEN added=true;
   IF fenced THEN RAISE EXCEPTION 'PAYMENT_ACCOUNT_QUIESCED'; END IF;
  ELSE
   added=NEW.payload->>'pendingEffectId' IS NOT NULL AND NEW.payload->'pendingEffectId' IS DISTINCT FROM OLD.payload->'pendingEffectId';
   IF fenced AND added AND OLD.state IN('COMPLETED','REVERSED','CANCELLED','EXPIRED')
   THEN RAISE EXCEPTION 'PAYMENT_ACCOUNT_QUIESCED'; END IF;
  END IF;
 ELSE
  SELECT state INTO parent_state FROM public.managed_payment_operation WHERE tenant_id=NEW.tenant_id AND payment_id=NEW.payment_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'RETAINED_PAYMENT_PARENT_REQUIRED'; END IF;
  IF TG_OP='INSERT' THEN added=NEW.state<>'ACKNOWLEDGED';
  ELSE added=OLD.state='ACKNOWLEDGED' AND NEW.state<>'ACKNOWLEDGED'; END IF;
  IF fenced AND added AND parent_state IN('COMPLETED','REVERSED','CANCELLED','EXPIRED')
  THEN RAISE EXCEPTION 'PAYMENT_ACCOUNT_QUIESCED'; END IF;
 END IF;
 IF added THEN
  IF EXISTS(SELECT 1 FROM public.managed_payment_account_gate WHERE tenant_id=NEW.tenant_id AND account_id IN(NEW.source_account_id,NEW.destination_account_id) AND source_revision=9223372036854775807)
  THEN RAISE EXCEPTION 'PAYMENT_SOURCE_REVISION_EXHAUSTED'; END IF;
  UPDATE public.managed_payment_account_gate SET source_revision=source_revision+1
   WHERE tenant_id=NEW.tenant_id AND account_id IN(NEW.source_account_id,NEW.destination_account_id);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER account_work_gate BEFORE INSERT OR UPDATE ON managed_payment_operation FOR EACH ROW EXECUTE FUNCTION public.guard_managed_payment_account_work();
CREATE TRIGGER account_work_gate BEFORE INSERT OR UPDATE ON managed_payment_effect FOR EACH ROW EXECUTE FUNCTION public.guard_managed_payment_account_work();

CREATE TABLE managed_payment_account_observation (
 tenant_id varchar(64) NOT NULL,account_id uuid NOT NULL,observation_id uuid NOT NULL,fence_id uuid NOT NULL,
 fence_version bigint NOT NULL,source_revision bigint NOT NULL,selection jsonb NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN('UNKNOWN','OBLIGATIONS','CLEAR')),total bigint NOT NULL CHECK(total>=0),
 obligation_count bigint NOT NULL CHECK(obligation_count>=0 AND obligation_count<=total),receipt jsonb NOT NULL,
 PRIMARY KEY(tenant_id,account_id,observation_id),
 FOREIGN KEY(tenant_id,account_id,fence_id) REFERENCES managed_payment_account_fence(tenant_id,account_id,fence_id)
);
CREATE TABLE managed_payment_account_observation_member (
 tenant_id varchar(64) NOT NULL,account_id uuid NOT NULL,observation_id uuid NOT NULL,obligation_id uuid NOT NULL,
 ordinal bigint NOT NULL CHECK(ordinal>0),direction varchar(16) NOT NULL CHECK(direction IN('INCOMING','OUTGOING')),
 state varchar(32) NOT NULL,effect_state varchar(16),blocking boolean NOT NULL,operation_version bigint NOT NULL,
 PRIMARY KEY(tenant_id,account_id,observation_id,obligation_id),UNIQUE(tenant_id,account_id,observation_id,ordinal),
 FOREIGN KEY(tenant_id,account_id,observation_id) REFERENCES managed_payment_account_observation(tenant_id,account_id,observation_id) DEFERRABLE INITIALLY DEFERRED
);
-- Raw producer references stay private; runtime sees only sanitized membership.
CREATE TABLE managed_payment_account_observation_reference (
 tenant_id varchar(64) NOT NULL,account_id uuid NOT NULL,observation_id uuid NOT NULL,obligation_id uuid NOT NULL,
 payment_id uuid NOT NULL,source_account_id uuid NOT NULL,destination_account_id uuid NOT NULL,
 operation_version bigint NOT NULL,effect_id uuid,effect_fence bigint,effect_attempts int,
 PRIMARY KEY(tenant_id,account_id,observation_id,obligation_id),UNIQUE(tenant_id,account_id,observation_id,payment_id),
 FOREIGN KEY(tenant_id,account_id,observation_id,obligation_id) REFERENCES managed_payment_account_observation_member(tenant_id,account_id,observation_id,obligation_id),
 FOREIGN KEY(tenant_id,payment_id,source_account_id,destination_account_id) REFERENCES managed_payment_operation(tenant_id,payment_id,source_account_id,destination_account_id),
 CHECK(account_id IN(source_account_id,destination_account_id))
);
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['managed_payment_account_observation','managed_payment_account_observation_member','managed_payment_account_observation_reference'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',tab);
  IF tab<>'managed_payment_account_observation_reference' THEN
   EXECUTE format('CREATE POLICY closure_summary_read ON public.%I FOR SELECT USING(public.payment_closure_scope(tenant_id,account_id))',tab);
  END IF;
  EXECUTE format('CREATE POLICY closure_capture_read ON public.%I FOR SELECT TO payment_closure_guard USING(public.payment_closure_scope(tenant_id,account_id))',tab);
  EXECUTE format('CREATE POLICY closure_capture_insert ON public.%I FOR INSERT TO payment_closure_guard WITH CHECK(public.payment_closure_scope(tenant_id,account_id) AND current_setting(''firefly.payment_closure.purpose'',true)=''observe'')',tab);
  EXECUTE format('CREATE TRIGGER retained_closure_evidence BEFORE UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.guard_payment_account_append_only()',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['managed_payment_operation','managed_payment_action','managed_payment_effect','managed_payment_projection','managed_payment_history','managed_payment_outbox'] LOOP
  EXECUTE format('GRANT SELECT ON public.%I TO payment_closure_guard',tab);
  EXECUTE format('CREATE POLICY closure_private_capture ON public.%I FOR SELECT TO payment_closure_guard USING(public.payment_closure_scope(tenant_id,source_account_id) OR public.payment_closure_scope(tenant_id,destination_account_id))',tab);
 END LOOP;
END $$;
GRANT USAGE ON SCHEMA public TO payment_closure_guard;
GRANT SELECT,INSERT,UPDATE ON managed_payment_account_gate TO payment_closure_guard;
GRANT SELECT ON managed_payment_account_fence TO payment_closure_guard;
GRANT SELECT,INSERT ON managed_payment_account_observation,managed_payment_account_observation_member,managed_payment_account_observation_reference TO payment_closure_guard;

CREATE FUNCTION public.managed_payment_account_capture(tenant text,account uuid,closure uuid,observation uuid) RETURNS jsonb
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE fence public.managed_payment_account_fence; gate public.managed_payment_account_gate; prior public.managed_payment_account_observation;
 item record; n bigint=0; blocking_count bigint=0; invalid boolean=false; blocks boolean; id uuid; receipt jsonb; status text;
BEGIN
 IF public.payment_closure_scope(tenant,account) IS NOT TRUE OR current_setting('firefly.payment_closure.purpose',true)<>'observe'
 THEN RAISE EXCEPTION 'CURRENT_COMPLETE_PAYMENT_OBSERVATION_AUTHORITY_REQUIRED'; END IF;
 PERFORM public.managed_payment_account_gate(tenant,account);
 SELECT * INTO fence FROM public.managed_payment_account_fence WHERE tenant_id=tenant AND account_id=account AND closure_id=closure;
 IF NOT FOUND OR fence.state<>'FENCED' THEN RAISE EXCEPTION 'RETAINED_ACTIVE_PAYMENT_FENCE_REQUIRED'; END IF;
 SELECT * INTO prior FROM public.managed_payment_account_observation WHERE tenant_id=tenant AND account_id=account AND observation_id=observation;
 IF FOUND THEN
  IF prior.fence_id<>fence.fence_id OR prior.selection IS DISTINCT FROM fence.selection THEN RAISE EXCEPTION 'PAYMENT_OBSERVATION_SELECTION_CONFLICT'; END IF;
  RETURN prior.receipt;
 END IF;
 SELECT * INTO gate FROM public.managed_payment_account_gate WHERE tenant_id=tenant AND account_id=account;
 FOR item IN SELECT o.*,e.effect_id,e.state AS effect_state,e.fence AS effect_fence,e.attempts AS effect_attempts
  FROM public.managed_payment_operation o LEFT JOIN public.managed_payment_effect e
   ON e.tenant_id=o.tenant_id AND e.payment_id=o.payment_id AND e.effect_id=(o.payload->>'pendingEffectId')::uuid
  WHERE o.tenant_id=tenant AND account IN(o.source_account_id,o.destination_account_id) ORDER BY o.payment_id LOOP
  n=n+1;id=gen_random_uuid();
  blocks=item.state NOT IN('COMPLETED','REVERSED','CANCELLED','EXPIRED') OR item.payload->>'pendingEffectId' IS NOT NULL
   OR EXISTS(SELECT 1 FROM public.managed_payment_effect e WHERE e.tenant_id=tenant AND e.payment_id=item.payment_id AND e.state<>'ACKNOWLEDGED');
  IF blocks THEN blocking_count=blocking_count+1; END IF;
  invalid=invalid OR (item.payload->>'pendingEffectId' IS NOT NULL AND item.effect_id IS NULL)
   OR NOT EXISTS(SELECT 1 FROM public.managed_payment_effect e WHERE e.tenant_id=tenant AND e.payment_id=item.payment_id AND e.kind='RESERVE')
   OR NOT EXISTS(SELECT 1 FROM public.managed_payment_action a WHERE a.tenant_id=tenant AND a.payment_id=item.payment_id AND a.action_id=item.create_action_id AND a.kind='CREATE')
   OR NOT EXISTS(SELECT 1 FROM public.managed_payment_projection p WHERE p.tenant_id=tenant AND p.payment_id=item.payment_id AND p.payload=item.payload)
   OR NOT EXISTS(SELECT 1 FROM public.managed_payment_history h WHERE h.tenant_id=tenant AND h.payment_id=item.payment_id AND h.version=item.version)
   OR NOT EXISTS(SELECT 1 FROM public.managed_payment_outbox b WHERE b.tenant_id=tenant AND b.payment_id=item.payment_id AND b.version=item.version)
   OR (item.state IN('COMPLETED','REVERSED','CANCELLED','EXPIRED') AND (item.payload->>'observedAt' IS NULL OR
    CASE WHEN item.state='EXPIRED' AND item.payload->>'ledgerState'='ABSENT_CLOSED' THEN
     coalesce(item.payload->>'absenceReceiptId','') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
     OR item.payload->'reservationId' IS DISTINCT FROM 'null'::jsonb
     OR item.payload->'ledgerVersion' IS DISTINCT FROM 'null'::jsonb
     OR item.payload->'postingId' IS DISTINCT FROM 'null'::jsonb
     OR item.payload->'reversalId' IS DISTINCT FROM 'null'::jsonb
     OR item.payload->>'notAfterEpochMillis' IS NULL
     OR extract(epoch FROM (item.payload->>'observedAt')::timestamptz)*1000 < (item.payload->>'notAfterEpochMillis')::bigint
    ELSE item.payload->>'ledgerVersion' IS NULL OR
     CASE item.state WHEN 'COMPLETED' THEN item.payload->>'ledgerState' IS DISTINCT FROM 'COMMITTED'
      WHEN 'REVERSED' THEN item.payload->>'ledgerState' IS DISTINCT FROM 'REVERSED'
      WHEN 'CANCELLED' THEN item.payload->>'ledgerState' IS DISTINCT FROM 'RELEASED'
      ELSE item.payload->>'ledgerState' IS DISTINCT FROM 'EXPIRED' END END));
  INSERT INTO public.managed_payment_account_observation_member VALUES(tenant,account,observation,id,n,CASE WHEN item.source_account_id=account THEN 'OUTGOING' ELSE 'INCOMING' END,item.state,item.effect_state,blocks,item.version);
  INSERT INTO public.managed_payment_account_observation_reference VALUES(tenant,account,observation,id,item.payment_id,item.source_account_id,item.destination_account_id,item.version,item.effect_id,item.effect_fence,item.effect_attempts);
 END LOOP;
 IF public.payment_closure_scope(tenant,account) IS NOT TRUE THEN RAISE EXCEPTION 'CURRENT_COMPLETE_PAYMENT_OBSERVATION_AUTHORITY_REQUIRED'; END IF;
 status=CASE WHEN invalid THEN 'UNKNOWN' WHEN blocking_count>0 THEN 'OBLIGATIONS' ELSE 'CLEAR' END;
 receipt=jsonb_build_object('observationId',observation,'fenceId',fence.fence_id,'closureId',closure,'selection',fence.selection,
  'fenceVersion',fence.version,'observedSourceVersion',gate.source_revision,'status',status,'total',n,'obligationCount',blocking_count,'observedAt',clock_timestamp());
 INSERT INTO public.managed_payment_account_observation VALUES(tenant,account,observation,fence.fence_id,fence.version,gate.source_revision,fence.selection,status,n,blocking_count,receipt);
 RETURN receipt;
END $$;
ALTER FUNCTION public.managed_payment_account_capture(text,uuid,uuid,uuid) OWNER TO payment_closure_guard;
REVOKE ALL ON FUNCTION public.managed_payment_account_capture(text,uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.managed_payment_account_capture(text,uuid,uuid,uuid) TO PUBLIC;

CREATE FUNCTION public.managed_payment_account_release(tenant text,account uuid,action uuid,closure uuid,expected bigint,digest text) RETURNS jsonb
 LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE gate public.managed_payment_account_gate; fence public.managed_payment_account_fence; prior public.managed_payment_account_action; receipt jsonb; intent jsonb;
BEGIN
 IF public.payment_closure_scope(tenant,account) IS NOT TRUE OR current_setting('firefly.payment_closure.purpose',true)<>'release'
 THEN RAISE EXCEPTION 'CURRENT_PAYMENT_RELEASE_AUTHORITY_REQUIRED'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('payment-account-closure-action:'||tenant||':'||action,0));
 PERFORM public.managed_payment_account_gate(tenant,account);
 intent=jsonb_build_object('actionId',action,'closureId',closure,'expectedFenceVersion',expected);
 SELECT * INTO prior FROM public.managed_payment_account_action WHERE tenant_id=tenant AND action_id=action;
 IF FOUND THEN
  IF prior.account_id<>account OR prior.kind<>'RELEASE' OR prior.request IS DISTINCT FROM intent OR prior.intent_digest<>digest
  THEN RAISE EXCEPTION 'PAYMENT_CLOSURE_ACTION_INTENT_CONFLICT'; END IF;
  RETURN prior.receipt;
 END IF;
 SELECT * INTO fence FROM public.managed_payment_account_fence WHERE tenant_id=tenant AND account_id=account AND closure_id=closure;
 IF NOT FOUND OR fence.state<>'FENCED' OR fence.version<>expected OR expected=9223372036854775807
 THEN RAISE EXCEPTION 'PAYMENT_ACCOUNT_FENCE_VERSION_CONFLICT'; END IF;
 SELECT * INTO gate FROM public.managed_payment_account_gate WHERE tenant_id=tenant AND account_id=account;
 IF gate.active_fence_id IS DISTINCT FROM fence.fence_id OR gate.fence_version<>expected
 THEN RAISE EXCEPTION 'PAYMENT_ACCOUNT_FENCE_VERSION_CONFLICT'; END IF;
 receipt=jsonb_build_object('fenceId',fence.fence_id,'actionId',action,'closureId',closure,'selection',fence.selection,'fenceVersion',expected+1,'releasedAt',clock_timestamp());
 INSERT INTO public.managed_payment_account_action VALUES(tenant,account,action,fence.fence_id,'RELEASE',digest,fence.selection,intent,receipt,current_setting('firefly.payment_closure.actor_issuer'),current_setting('firefly.payment_closure.actor_subject'));
 INSERT INTO public.managed_payment_account_history VALUES(tenant,account,fence.fence_id,expected+1,action,receipt);
 INSERT INTO public.managed_payment_account_outbox VALUES(tenant,account,fence.fence_id,expected+1,action,gen_random_uuid(),receipt);
 UPDATE public.managed_payment_account_fence SET state='RELEASED',version=expected+1,last_action_id=action WHERE tenant_id=tenant AND account_id=account AND fence_id=fence.fence_id;
 UPDATE public.managed_payment_account_gate SET active_fence_id=NULL,fence_version=expected+1 WHERE tenant_id=tenant AND account_id=account;
 RETURN receipt;
END $$;

CREATE FUNCTION public.guard_payment_account_gate() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'STICKY_PAYMENT_ACCOUNT_GATE_REQUIRED'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.source_revision<>0 OR NEW.fence_version<>0 OR NEW.active_fence_id IS NOT NULL
  THEN RAISE EXCEPTION 'FRESH_PAYMENT_ACCOUNT_GATE_REQUIRED'; END IF;
  RETURN NEW;
 END IF;
 IF (NEW.tenant_id,NEW.account_id) IS DISTINCT FROM (OLD.tenant_id,OLD.account_id)
 THEN RAISE EXCEPTION 'IMMUTABLE_PAYMENT_ACCOUNT_GATE_IDENTITY'; END IF;
 IF NEW.source_revision IS DISTINCT FROM OLD.source_revision THEN
  IF OLD.source_revision=9223372036854775807 OR NEW.source_revision<>OLD.source_revision+1
   OR coalesce(current_setting('firefly.payment.purpose',true),'') NOT IN('CREATE','MUTATE','OBSERVE')
   OR public.payment_account_gate_scope(NEW.tenant_id,NEW.account_id) IS NOT TRUE
  THEN RAISE EXCEPTION 'MONOTONIC_PAYMENT_SOURCE_REVISION_REQUIRED'; END IF;
 END IF;
 IF (NEW.fence_version,NEW.active_fence_id) IS DISTINCT FROM (OLD.fence_version,OLD.active_fence_id) THEN
  IF OLD.fence_version=9223372036854775807 OR NEW.fence_version<>OLD.fence_version+1
   OR public.payment_closure_scope(NEW.tenant_id,NEW.account_id) IS NOT TRUE
   OR coalesce(current_setting('firefly.payment_closure.purpose',true),'') NOT IN('quiesce','release')
  THEN RAISE EXCEPTION 'EXPLICIT_CURRENT_PAYMENT_FENCE_COMMAND_REQUIRED'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sticky_account_gate BEFORE INSERT OR UPDATE OR DELETE ON managed_payment_account_gate FOR EACH ROW EXECUTE FUNCTION public.guard_payment_account_gate();

CREATE CONSTRAINT TRIGGER complete_account_gate_insert AFTER INSERT ON managed_payment_account_gate
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_complete_payment_account_fence();
CREATE CONSTRAINT TRIGGER complete_account_gate_update AFTER UPDATE ON managed_payment_account_gate
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 WHEN (OLD.fence_version IS DISTINCT FROM NEW.fence_version OR OLD.active_fence_id IS DISTINCT FROM NEW.active_fence_id)
 EXECUTE FUNCTION public.require_complete_payment_account_fence();
