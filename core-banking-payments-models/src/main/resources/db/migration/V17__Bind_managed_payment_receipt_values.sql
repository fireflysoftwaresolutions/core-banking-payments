-- Forward-only canonical receipt binding. Normal Flyway transactional migration;
-- existing V1-V16 and retained values are not rewritten. Invalid retained rows abort installation.
-- The public ABI below is row-free/invoker-only; it grants no business table or authority access.
-- Database execution/activation requires separate owner and runtime qualification.
SET LOCAL search_path=pg_catalog;

DO $install$
DECLARE relation_owner oid; installer oid;
BEGIN
 SELECT c.relowner INTO STRICT relation_owner FROM pg_catalog.pg_class c
 JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname='managed_payment_monetary_proof' AND c.relkind='r';
 SELECT r.oid INTO STRICT installer FROM pg_catalog.pg_roles r WHERE r.rolname=current_user;
 IF current_user<>session_user OR installer<>relation_owner
  OR pg_catalog.current_setting('server_encoding')<>'UTF8' THEN
  RAISE EXCEPTION 'PAYMENT_CANONICAL_INSTALLER_REQUIRED' USING ERRCODE='42501';
 END IF;
 IF (SELECT pg_catalog.count(*)=15 AND pg_catalog.bool_and(COALESCE(
 a.attname=e.name AND n.nspname='pg_catalog' AND t.typname=e.type AND a.atttypmod=e.modifier
 AND a.attnotnull AND NOT a.attisdropped AND a.attgenerated='' AND a.attidentity='',false))
 FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
 JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
 FULL JOIN (VALUES
  (1,'tenant_id','varchar',68),
  (2,'payment_id','uuid',-1),
  (3,'source_account_id','uuid',-1),
  (4,'destination_account_id','uuid',-1),
  (5,'effect_id','uuid',-1),
  (6,'participant_action_id','uuid',-1),
  (7,'kind','varchar',20),
  (8,'receipt_id','uuid',-1),
  (9,'retained_version','int8',-1),
  (10,'capture_fence','int8',-1),
  (11,'observed_reservation_version','int8',-1),
  (12,'observed_at','timestamptz',-1),
  (13,'observer_subject','varchar',259),
  (14,'receipt_envelope','jsonb',-1),
  (15,'retained_at','timestamptz',-1)) e(number,name,type,modifier) ON e.number=a.attnum
 WHERE a.attrelid='public.managed_payment_monetary_proof'::pg_catalog.regclass AND a.attnum>0) IS NOT TRUE THEN
  RAISE EXCEPTION 'PAYMENT_CANONICAL_LAYOUT_REQUIRED' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
  WHERE n.nspname='public' AND (t.typname=ANY(ARRAY['payment_receipt_snapshot_v1','payment_receipt_balances_v1','payment_receipt_accepted_reserve_v1']::pg_catalog.text[])
   OR t.typname=ANY(ARRAY['_payment_receipt_snapshot_v1','_payment_receipt_balances_v1','_payment_receipt_accepted_reserve_v1']::pg_catalog.text[])))
  OR EXISTS(SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
   WHERE n.nspname='public' AND p.proname=ANY(ARRAY['payment_receipt_require','payment_receipt_utf16_length','payment_receipt_is_blank','payment_receipt_text_valid','payment_receipt_lp_text','payment_receipt_b64','payment_receipt_numeric_text','payment_receipt_minor_text','payment_receipt_snapshot_bytes','payment_receipt_balances_bytes','payment_receipt_intent_bytes','payment_receipt_accepted_bytes','payment_receipt_receipt_bytes','payment_receipt_envelope_bound','payment_receipt_row_bound']::pg_catalog.text[]))
  OR EXISTS(SELECT 1 FROM pg_catalog.pg_constraint c
   WHERE c.conrelid='public.managed_payment_monetary_proof'::pg_catalog.regclass
    AND c.conname='managed_monetary_canonical_value') THEN
  RAISE EXCEPTION 'PAYMENT_CANONICAL_NAMESPACE_COLLISION' USING ERRCODE='42710';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_catalog.pg_default_acl d
  CROSS JOIN LATERAL pg_catalog.aclexplode(d.defaclacl) a
  WHERE d.defaclrole=relation_owner AND d.defaclnamespace IN(0,'public'::pg_catalog.regnamespace)
   AND d.defaclobjtype IN('f','T') AND (a.grantee NOT IN(0,relation_owner)
    OR a.grantor<>relation_owner OR a.is_grantable
    OR a.privilege_type<>CASE d.defaclobjtype WHEN 'f' THEN 'EXECUTE' ELSE 'USAGE' END)) THEN
  RAISE EXCEPTION 'PAYMENT_CANONICAL_DEFAULT_ACL_REFUSED' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang
  WHERE p.oid='public.managed_payment_repository_runtime()'::pg_catalog.regprocedure
   AND p.proowner=relation_owner AND NOT p.prosecdef AND p.provolatile='v' AND l.lanname='sql'
   AND p.prorettype='pg_catalog.bool'::pg_catalog.regtype AND NOT p.proretset AND p.prokind='f'
   AND p.proargmodes IS NULL AND p.proallargtypes IS NULL AND p.provariadic=0
   AND p.proconfig=ARRAY['search_path=pg_catalog, public, pg_temp']
   AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p.prosrc,'UTF8')),'hex')='bfd4909efc6631aaa3e884bebabb8956ab847d03620f6111462e72d6cb54f52a') THEN
  RAISE EXCEPTION 'PAYMENT_CANONICAL_PREDECESSOR_REQUIRED' USING ERRCODE='23514';
 END IF;
END
$install$;

CREATE TYPE public.payment_receipt_snapshot_v1 AS (account_id UUID,enrollment_id UUID,contract_id UUID,
 currency TEXT,currency_scale INTEGER,currency_policy TEXT,kind TEXT);
CREATE TYPE public.payment_receipt_balances_v1 AS (booked_before NUMERIC,held_before NUMERIC,booked_after NUMERIC,held_after NUMERIC);
CREATE TYPE public.payment_receipt_accepted_reserve_v1 AS (tenant TEXT,reservation_id UUID,operation_id UUID,
 source_account_id UUID,destination_account_id UUID,amount NUMERIC,currency TEXT,ttl_seconds INTEGER,
 not_after_epoch_millis BIGINT,intent_recorded_at_epoch_millis BIGINT,expires_at_epoch_millis BIGINT,
 source_snapshot public.payment_receipt_snapshot_v1,destination_snapshot public.payment_receipt_snapshot_v1);

CREATE FUNCTION public.payment_receipt_require(valid BOOLEAN) RETURNS VOID
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
BEGIN
 IF valid IS NOT TRUE THEN RAISE EXCEPTION 'FND_RECEIPT_INVALID_CANONICAL' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION public.payment_receipt_utf16_length(value TEXT) RETURNS BIGINT
 LANGUAGE SQL IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT COALESCE(sum(CASE WHEN ascii(c)>65535 THEN 2 ELSE 1 END),0)
 FROM regexp_split_to_table(value,'') AS chars(c) WHERE c<>''
$$;
CREATE FUNCTION public.payment_receipt_is_blank(value TEXT) RETURNS BOOLEAN
 LANGUAGE SQL IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT NOT EXISTS(SELECT 1 FROM regexp_split_to_table(value,'') AS chars(c)
 WHERE c<>'' AND ascii(c) NOT BETWEEN 9 AND 13 AND ascii(c) NOT BETWEEN 28 AND 32
 AND ascii(c)<>5760 AND ascii(c) NOT BETWEEN 8192 AND 8198 AND ascii(c) NOT BETWEEN 8200 AND 8202
 AND ascii(c) NOT IN(8232,8233,8287,12288))
$$;
CREATE FUNCTION public.payment_receipt_text_valid(value TEXT,max_bytes INTEGER) RETURNS BOOLEAN
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
BEGIN
 IF value IS NULL OR max_bytes IS NULL OR max_bytes<0 OR octet_length(value)>max_bytes THEN RETURN FALSE; END IF;
 RETURN NOT public.payment_receipt_is_blank(value);
END
$$;
CREATE FUNCTION public.payment_receipt_lp_text(value TEXT,max_bytes INTEGER DEFAULT 16384) RETURNS BYTEA
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
BEGIN
 PERFORM public.payment_receipt_require(max_bytes BETWEEN 0 AND 16384);
 IF value IS NULL THEN RETURN int4send(-1); END IF;
 PERFORM public.payment_receipt_require(octet_length(value)<=max_bytes);
 RETURN int4send(octet_length(value))||convert_to(value,'UTF8');
END $$;
CREATE FUNCTION public.payment_receipt_b64(value BYTEA,max_encoded INTEGER DEFAULT 65536) RETURNS TEXT
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE result TEXT;
BEGIN
 PERFORM public.payment_receipt_require(value IS NOT NULL AND max_encoded BETWEEN 4 AND 65536);
 PERFORM public.payment_receipt_require(octet_length(value)<=max_encoded*3/4);
 result:=replace(encode(value,'base64'),E'\n','');
 PERFORM public.payment_receipt_require(length(result)<=max_encoded);
 RETURN result;
END $$;
CREATE FUNCTION public.payment_receipt_numeric_text(value NUMERIC) RETURNS TEXT
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
BEGIN
 PERFORM public.payment_receipt_require(value IS NOT NULL AND value<>'NaN'::numeric
  AND value NOT IN('Infinity'::numeric,'-Infinity'::numeric)
  AND abs(value)<100000000000000000000::numeric AND value=round(value,18));
 RETURN trim_scale(value)::text;
END $$;
CREATE FUNCTION public.payment_receipt_minor_text(value NUMERIC,actual_scale INTEGER) RETURNS TEXT
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE scaled NUMERIC;
BEGIN
 PERFORM public.payment_receipt_numeric_text(value);
 PERFORM public.payment_receipt_require(actual_scale BETWEEN 0 AND 18);
 scaled:=value*power(10::numeric,actual_scale);
 PERFORM public.payment_receipt_require(scaled=trunc(scaled));
 RETURN trim_scale(scaled)::text;
END $$;
CREATE FUNCTION public.payment_receipt_snapshot_bytes(s public.payment_receipt_snapshot_v1) RETURNS BYTEA
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
BEGIN
 PERFORM public.payment_receipt_require(s.account_id IS NOT NULL AND s.enrollment_id IS NOT NULL AND s.contract_id IS NOT NULL
  AND (s.currency COLLATE "C")~'^[A-Z]{3}$' AND s.currency_scale BETWEEN 0 AND 18
  AND s.kind IN('CUSTOMER','BANK_CONTROL'));
 PERFORM public.payment_receipt_require(public.payment_receipt_text_valid(s.currency_policy,400));
 PERFORM public.payment_receipt_require(public.payment_receipt_utf16_length(s.currency_policy)<=100);
 RETURN public.payment_receipt_lp_text(s.account_id::text)||public.payment_receipt_lp_text(s.enrollment_id::text)
  ||public.payment_receipt_lp_text(s.contract_id::text)||public.payment_receipt_lp_text(s.currency)
  ||public.payment_receipt_lp_text(s.currency_scale::text)||public.payment_receipt_lp_text(s.currency_policy)||public.payment_receipt_lp_text(s.kind);
END $$;
CREATE FUNCTION public.payment_receipt_balances_bytes(b public.payment_receipt_balances_v1) RETURNS BYTEA
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
BEGIN
 RETURN public.payment_receipt_lp_text(public.payment_receipt_numeric_text(b.booked_before))
  ||public.payment_receipt_lp_text(public.payment_receipt_numeric_text(b.held_before))
  ||public.payment_receipt_lp_text(public.payment_receipt_numeric_text(b.booked_after))
  ||public.payment_receipt_lp_text(public.payment_receipt_numeric_text(b.held_after));
END $$;
CREATE FUNCTION public.payment_receipt_intent_bytes(tenant TEXT,operation UUID,source UUID,destination UUID,
 amount NUMERIC,currency TEXT,ttl INTEGER,deadline BIGINT) RETURNS BYTEA
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE result BYTEA;
BEGIN
 PERFORM public.payment_receipt_numeric_text(amount);
 PERFORM public.payment_receipt_require((tenant COLLATE "C")~'^[A-Za-z0-9_-]{1,64}$'
  AND operation IS NOT NULL AND source IS NOT NULL AND destination IS NOT NULL AND source<>destination
  AND amount>0 AND (currency COLLATE "C")~'^[A-Z]{3}$' AND ttl BETWEEN 1 AND 900
  AND (deadline IS NULL OR deadline BETWEEN 1 AND 253402300799999));
 result:=public.payment_receipt_lp_text('FND_RESERVE_INTENT_V1')||public.payment_receipt_lp_text(tenant)
  ||public.payment_receipt_lp_text(operation::text)||public.payment_receipt_lp_text(source::text)||public.payment_receipt_lp_text(destination::text)
  ||public.payment_receipt_lp_text(public.payment_receipt_numeric_text(amount))||public.payment_receipt_lp_text(currency)
  ||public.payment_receipt_lp_text(ttl::text)||public.payment_receipt_lp_text(deadline::text);
 PERFORM public.payment_receipt_b64(result,4096);
 RETURN result;
END $$;
CREATE FUNCTION public.payment_receipt_accepted_bytes(a public.payment_receipt_accepted_reserve_v1) RETURNS BYTEA
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE original BYTEA;source_bytes BYTEA;destination_bytes BYTEA;result BYTEA;
BEGIN
 original:=public.payment_receipt_intent_bytes(a.tenant,a.operation_id,a.source_account_id,a.destination_account_id,
  a.amount,a.currency,a.ttl_seconds,a.not_after_epoch_millis);
 source_bytes:=public.payment_receipt_snapshot_bytes(a.source_snapshot);
 destination_bytes:=public.payment_receipt_snapshot_bytes(a.destination_snapshot);
 PERFORM public.payment_receipt_require(a.reservation_id IS NOT NULL
  AND a.intent_recorded_at_epoch_millis BETWEEN 1 AND 253402300799999
  AND a.expires_at_epoch_millis BETWEEN 1 AND 253402300799999);
 PERFORM public.payment_receipt_require(a.expires_at_epoch_millis>a.intent_recorded_at_epoch_millis
  AND a.expires_at_epoch_millis=least(a.intent_recorded_at_epoch_millis+a.ttl_seconds*1000::bigint,
   coalesce(a.not_after_epoch_millis,253402300799999))
  AND (a.source_snapshot).account_id=a.source_account_id AND (a.destination_snapshot).account_id=a.destination_account_id
  AND (a.source_snapshot).enrollment_id<>(a.destination_snapshot).enrollment_id
  AND (a.source_snapshot).currency=a.currency AND (a.destination_snapshot).currency=a.currency
  AND (a.source_snapshot).kind='CUSTOMER' AND (a.destination_snapshot).kind='CUSTOMER');
 PERFORM public.payment_receipt_minor_text(a.amount,(a.source_snapshot).currency_scale);
 PERFORM public.payment_receipt_minor_text(a.amount,(a.destination_snapshot).currency_scale);
 result:=public.payment_receipt_lp_text('FND_ACCEPTED_RESERVE_V1')||public.payment_receipt_lp_text(public.payment_receipt_b64(original,4096))
  ||public.payment_receipt_lp_text(a.reservation_id::text)||public.payment_receipt_lp_text(a.intent_recorded_at_epoch_millis::text)
  ||public.payment_receipt_lp_text(a.expires_at_epoch_millis::text)||source_bytes||destination_bytes;
 PERFORM public.payment_receipt_b64(result,16384);
 RETURN result;
END $$;
CREATE FUNCTION public.payment_receipt_receipt_bytes(receipt UUID,event UUID,tenant TEXT,reservation UUID,operation UUID,
 kind TEXT,action UUID,a public.payment_receipt_accepted_reserve_v1,posting UUID,original_receipt UUID,original_posting UUID,
 recorded BIGINT,amount NUMERIC,currency TEXT,source_snapshot public.payment_receipt_snapshot_v1,
 destination_snapshot public.payment_receipt_snapshot_v1,source_minor TEXT,destination_minor TEXT,
 flow_from UUID,flow_to UUID,sb public.payment_receipt_balances_v1,db public.payment_receipt_balances_v1,issuer TEXT,subject TEXT)
 RETURNS BYTEA LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE accepted_bytes BYTEA;source_bytes BYTEA;destination_bytes BYTEA;source_balances BYTEA;
 destination_balances BYTEA;reverse BOOLEAN;result BYTEA;
BEGIN
 accepted_bytes:=public.payment_receipt_accepted_bytes(a);
 PERFORM public.payment_receipt_numeric_text(amount);
 source_bytes:=public.payment_receipt_snapshot_bytes(source_snapshot);
 destination_bytes:=public.payment_receipt_snapshot_bytes(destination_snapshot);
 source_balances:=public.payment_receipt_balances_bytes(sb);destination_balances:=public.payment_receipt_balances_bytes(db);
 PERFORM public.payment_receipt_require(receipt IS NOT NULL AND event=receipt AND action IS NOT NULL AND posting IS NOT NULL
  AND (tenant COLLATE "C")=(a.tenant COLLATE "C") AND reservation=a.reservation_id AND operation=a.operation_id
  AND kind IN('COMMIT','REVERSE') AND recorded BETWEEN 1 AND 253402300799999
  AND recorded>=a.intent_recorded_at_epoch_millis AND amount=a.amount AND currency=a.currency
  AND source_bytes=public.payment_receipt_snapshot_bytes(a.source_snapshot)
  AND destination_bytes=public.payment_receipt_snapshot_bytes(a.destination_snapshot)
  AND source_minor=public.payment_receipt_minor_text(amount,source_snapshot.currency_scale)
  AND destination_minor=public.payment_receipt_minor_text(amount,destination_snapshot.currency_scale)
  AND public.payment_receipt_text_valid(issuer,2048) AND public.payment_receipt_text_valid(subject,800)
  AND sb.held_before>=0 AND sb.held_after>=0 AND db.held_before>=0 AND db.held_after>=0
  AND sb.booked_before>=sb.held_before AND sb.booked_after>=sb.held_after
  AND db.booked_before>=db.held_before AND db.booked_after>=db.held_after);
 PERFORM public.payment_receipt_minor_text(sb.booked_before,source_snapshot.currency_scale);
 PERFORM public.payment_receipt_minor_text(sb.held_before,source_snapshot.currency_scale);
 PERFORM public.payment_receipt_minor_text(sb.booked_after,source_snapshot.currency_scale);
 PERFORM public.payment_receipt_minor_text(sb.held_after,source_snapshot.currency_scale);
 PERFORM public.payment_receipt_minor_text(db.booked_before,destination_snapshot.currency_scale);
 PERFORM public.payment_receipt_minor_text(db.held_before,destination_snapshot.currency_scale);
 PERFORM public.payment_receipt_minor_text(db.booked_after,destination_snapshot.currency_scale);
 PERFORM public.payment_receipt_minor_text(db.held_after,destination_snapshot.currency_scale);
 reverse:=kind='REVERSE';
 IF reverse THEN
  PERFORM public.payment_receipt_require(original_receipt IS NOT NULL AND original_posting IS NOT NULL
   AND receipt<>original_receipt AND posting<>original_posting);
 ELSE
  PERFORM public.payment_receipt_require(original_receipt IS NULL AND original_posting IS NULL AND recorded<a.expires_at_epoch_millis);
 END IF;
 PERFORM public.payment_receipt_require(flow_from=CASE WHEN reverse THEN destination_snapshot.account_id ELSE source_snapshot.account_id END
  AND flow_to=CASE WHEN reverse THEN source_snapshot.account_id ELSE destination_snapshot.account_id END
  AND sb.booked_after=sb.booked_before+CASE WHEN reverse THEN amount ELSE -amount END
  AND db.booked_after=db.booked_before+CASE WHEN reverse THEN -amount ELSE amount END
  AND sb.held_after=CASE WHEN reverse THEN sb.held_before ELSE sb.held_before-amount END
  AND db.held_after=db.held_before);
 result:=public.payment_receipt_lp_text('FND_MONETARY_RECEIPT_V1')||public.payment_receipt_lp_text(receipt::text)
  ||public.payment_receipt_lp_text(tenant)||public.payment_receipt_lp_text(reservation::text)||public.payment_receipt_lp_text(operation::text)
  ||public.payment_receipt_lp_text(kind)||public.payment_receipt_lp_text(action::text)||public.payment_receipt_lp_text(public.payment_receipt_b64(accepted_bytes,16384))
  ||public.payment_receipt_lp_text(posting::text)||public.payment_receipt_lp_text(original_receipt::text)||public.payment_receipt_lp_text(original_posting::text)
  ||public.payment_receipt_lp_text(recorded::text)||public.payment_receipt_lp_text(public.payment_receipt_numeric_text(amount))||public.payment_receipt_lp_text(currency)
  ||source_bytes||destination_bytes||public.payment_receipt_lp_text(source_minor)||public.payment_receipt_lp_text(destination_minor)
  ||public.payment_receipt_lp_text(flow_from::text)||public.payment_receipt_lp_text(flow_to::text)||source_balances||destination_balances
  ||public.payment_receipt_lp_text(issuer)||public.payment_receipt_lp_text(subject);
 PERFORM public.payment_receipt_b64(result,65536);
 RETURN result;
END $$;


CREATE FUNCTION public.payment_receipt_envelope_bound(e pg_catalog.jsonb) RETURNS pg_catalog.bool
 LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER SET search_path=pg_catalog AS $bridge$
DECLARE v jsonb;form jsonb;a jsonb;i jsonb;m jsonb;asrc jsonb;adst jsonb;esrc jsonb;edst jsonb;
 sb jsonb;db jsonb;actor jsonb;object_value jsonb;required_keys text[];
 accepted public.payment_receipt_accepted_reserve_v1;encoded bytea;
BEGIN
 PERFORM public.payment_receipt_require(e IS NOT NULL AND pg_catalog.octet_length(e::text)<=2097152);
 v:=e->'value';form:=e->'canonical';a:=v->'acceptedReserve';i:=a->'intent';m:=v->'effect';
 asrc:=a->'source';adst:=a->'destination';esrc:=m->'source';edst:=m->'destination';
 sb:=m->'sourceBalances';db:=m->'destinationBalances';actor:=v->'actor';
 FOR object_value,required_keys IN SELECT * FROM (VALUES
  (e,ARRAY['canonical','value']::pg_catalog.text[]),
  (v,ARRAY['acceptedReserve','actionId','actor','effect','effectKind','effectRecordedAtEpochMillis','eventId','operationId','originalCommitReceiptId','originalPostingId','postingId','receiptId','reservationId','tenant']::pg_catalog.text[]),
  (form,ARRAY['canonical','digest','encoding']::pg_catalog.text[]),
  (a,ARRAY['destination','expiresAtEpochMillis','intent','intentRecordedAtEpochMillis','reservationId','source','tenant']::pg_catalog.text[]),
  (i,ARRAY['amount','currency','destinationAccountId','notAfterEpochMillis','operationId','sourceAccountId','ttlSeconds']::pg_catalog.text[]),
  (m,ARRAY['amount','currency','destination','destinationBalances','destinationMinorUnits','fromAccountId','source','sourceBalances','sourceMinorUnits','toAccountId']::pg_catalog.text[]),
  (sb,ARRAY['bookedAfter','bookedBefore','heldAfter','heldBefore']::pg_catalog.text[]),
  (db,ARRAY['bookedAfter','bookedBefore','heldAfter','heldBefore']::pg_catalog.text[]),
  (actor,ARRAY['issuer','subject']::pg_catalog.text[]),
  (asrc,ARRAY['accountId','contractId','currency','currencyPolicy','currencyScale','enrollmentId','kind']::pg_catalog.text[]),
  (adst,ARRAY['accountId','contractId','currency','currencyPolicy','currencyScale','enrollmentId','kind']::pg_catalog.text[]),
  (esrc,ARRAY['accountId','contractId','currency','currencyPolicy','currencyScale','enrollmentId','kind']::pg_catalog.text[]),
  (edst,ARRAY['accountId','contractId','currency','currencyPolicy','currencyScale','enrollmentId','kind']::pg_catalog.text[])) AS shapes(object_value,required_keys) LOOP
  PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof(object_value)='object');
  PERFORM public.payment_receipt_require((SELECT pg_catalog.array_agg(k COLLATE "C" ORDER BY k COLLATE "C")
   FROM pg_catalog.jsonb_object_keys(object_value) AS keys(k))=required_keys);
 END LOOP;
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((v->'receiptId'))='string' AND ((v->>'receiptId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((v->'eventId'))='string' AND ((v->>'eventId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((v->'tenant'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((v->'reservationId'))='string' AND ((v->>'reservationId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((v->'operationId'))='string' AND ((v->>'operationId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((v->'effectKind'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((v->'actionId'))='string' AND ((v->>'actionId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((v->'postingId'))='string' AND ((v->>'postingId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require((v->'originalCommitReceiptId')='null'::pg_catalog.jsonb OR (pg_catalog.jsonb_typeof((v->'originalCommitReceiptId'))='string' AND ((v->>'originalCommitReceiptId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$'));
 PERFORM public.payment_receipt_require((v->'originalPostingId')='null'::pg_catalog.jsonb OR (pg_catalog.jsonb_typeof((v->'originalPostingId'))='string' AND ((v->>'originalPostingId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$'));
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((v->'effectRecordedAtEpochMillis'))='number' AND (v->>'effectRecordedAtEpochMillis')::pg_catalog.numeric=pg_catalog.trunc((v->>'effectRecordedAtEpochMillis')::pg_catalog.numeric) AND (v->>'effectRecordedAtEpochMillis')::pg_catalog.numeric BETWEEN 1 AND 253402300799999);
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((form->'encoding'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((form->'canonical'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((form->'digest'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((a->'tenant'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((a->'reservationId'))='string' AND ((a->>'reservationId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((a->'intentRecordedAtEpochMillis'))='number' AND (a->>'intentRecordedAtEpochMillis')::pg_catalog.numeric=pg_catalog.trunc((a->>'intentRecordedAtEpochMillis')::pg_catalog.numeric) AND (a->>'intentRecordedAtEpochMillis')::pg_catalog.numeric BETWEEN 1 AND 253402300799999);
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((a->'expiresAtEpochMillis'))='number' AND (a->>'expiresAtEpochMillis')::pg_catalog.numeric=pg_catalog.trunc((a->>'expiresAtEpochMillis')::pg_catalog.numeric) AND (a->>'expiresAtEpochMillis')::pg_catalog.numeric BETWEEN 1 AND 253402300799999);
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((i->'operationId'))='string' AND ((i->>'operationId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((i->'sourceAccountId'))='string' AND ((i->>'sourceAccountId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((i->'destinationAccountId'))='string' AND ((i->>'destinationAccountId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((i->'amount'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((i->'currency'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((i->'ttlSeconds'))='number' AND (i->>'ttlSeconds')::pg_catalog.numeric=pg_catalog.trunc((i->>'ttlSeconds')::pg_catalog.numeric) AND (i->>'ttlSeconds')::pg_catalog.numeric BETWEEN 1 AND 900);
 PERFORM public.payment_receipt_require((i->'notAfterEpochMillis')='null'::pg_catalog.jsonb OR (pg_catalog.jsonb_typeof((i->'notAfterEpochMillis'))='number' AND (i->>'notAfterEpochMillis')::pg_catalog.numeric=pg_catalog.trunc((i->>'notAfterEpochMillis')::pg_catalog.numeric) AND (i->>'notAfterEpochMillis')::pg_catalog.numeric BETWEEN 1 AND 253402300799999));
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((m->'amount'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((m->'currency'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((m->'sourceMinorUnits'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((m->'destinationMinorUnits'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((m->'fromAccountId'))='string' AND ((m->>'fromAccountId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((m->'toAccountId'))='string' AND ((m->>'toAccountId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((sb->'bookedBefore'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((sb->'heldBefore'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((sb->'bookedAfter'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((sb->'heldAfter'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((db->'bookedBefore'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((db->'heldBefore'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((db->'bookedAfter'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((db->'heldAfter'))='number');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((actor->'issuer'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((actor->'subject'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((asrc->'accountId'))='string' AND ((asrc->>'accountId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((asrc->'enrollmentId'))='string' AND ((asrc->>'enrollmentId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((asrc->'contractId'))='string' AND ((asrc->>'contractId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((asrc->'currency'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((asrc->'currencyScale'))='number' AND (asrc->>'currencyScale')::pg_catalog.numeric=pg_catalog.trunc((asrc->>'currencyScale')::pg_catalog.numeric) AND (asrc->>'currencyScale')::pg_catalog.numeric BETWEEN 0 AND 18);
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((asrc->'currencyPolicy'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((asrc->'kind'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((adst->'accountId'))='string' AND ((adst->>'accountId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((adst->'enrollmentId'))='string' AND ((adst->>'enrollmentId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((adst->'contractId'))='string' AND ((adst->>'contractId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((adst->'currency'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((adst->'currencyScale'))='number' AND (adst->>'currencyScale')::pg_catalog.numeric=pg_catalog.trunc((adst->>'currencyScale')::pg_catalog.numeric) AND (adst->>'currencyScale')::pg_catalog.numeric BETWEEN 0 AND 18);
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((adst->'currencyPolicy'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((adst->'kind'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((esrc->'accountId'))='string' AND ((esrc->>'accountId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((esrc->'enrollmentId'))='string' AND ((esrc->>'enrollmentId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((esrc->'contractId'))='string' AND ((esrc->>'contractId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((esrc->'currency'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((esrc->'currencyScale'))='number' AND (esrc->>'currencyScale')::pg_catalog.numeric=pg_catalog.trunc((esrc->>'currencyScale')::pg_catalog.numeric) AND (esrc->>'currencyScale')::pg_catalog.numeric BETWEEN 0 AND 18);
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((esrc->'currencyPolicy'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((esrc->'kind'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((edst->'accountId'))='string' AND ((edst->>'accountId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((edst->'enrollmentId'))='string' AND ((edst->>'enrollmentId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((edst->'contractId'))='string' AND ((edst->>'contractId') COLLATE "C")~'^[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((edst->'currency'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((edst->'currencyScale'))='number' AND (edst->>'currencyScale')::pg_catalog.numeric=pg_catalog.trunc((edst->>'currencyScale')::pg_catalog.numeric) AND (edst->>'currencyScale')::pg_catalog.numeric BETWEEN 0 AND 18);
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((edst->'currencyPolicy'))='string');
 PERFORM public.payment_receipt_require(pg_catalog.jsonb_typeof((edst->'kind'))='string');
 PERFORM public.payment_receipt_require(((v->>'effectKind') COLLATE "C") IN('COMMIT','REVERSE'));
 PERFORM public.payment_receipt_require(((asrc->>'kind') COLLATE "C") IN('CUSTOMER','BANK_CONTROL')
  AND ((adst->>'kind') COLLATE "C") IN('CUSTOMER','BANK_CONTROL')
  AND ((esrc->>'kind') COLLATE "C") IN('CUSTOMER','BANK_CONTROL')
  AND ((edst->>'kind') COLLATE "C") IN('CUSTOMER','BANK_CONTROL'));
 accepted:=ROW(a->>'tenant',(a->>'reservationId')::pg_catalog.uuid,(i->>'operationId')::pg_catalog.uuid,
  (i->>'sourceAccountId')::pg_catalog.uuid,(i->>'destinationAccountId')::pg_catalog.uuid,
  (i->>'amount')::pg_catalog.numeric,i->>'currency',(i->>'ttlSeconds')::pg_catalog.numeric::pg_catalog.int4,
  (i->>'notAfterEpochMillis')::pg_catalog.numeric::pg_catalog.int8,
  (a->>'intentRecordedAtEpochMillis')::pg_catalog.numeric::pg_catalog.int8,
  (a->>'expiresAtEpochMillis')::pg_catalog.numeric::pg_catalog.int8,
  ROW((asrc->>'accountId')::pg_catalog.uuid,(asrc->>'enrollmentId')::pg_catalog.uuid,(asrc->>'contractId')::pg_catalog.uuid,(asrc->>'currency')::pg_catalog.text,(asrc->>'currencyScale')::pg_catalog.numeric::pg_catalog.int4,(asrc->>'currencyPolicy')::pg_catalog.text,(asrc->>'kind')::pg_catalog.text)::public.payment_receipt_snapshot_v1,ROW((adst->>'accountId')::pg_catalog.uuid,(adst->>'enrollmentId')::pg_catalog.uuid,(adst->>'contractId')::pg_catalog.uuid,(adst->>'currency')::pg_catalog.text,(adst->>'currencyScale')::pg_catalog.numeric::pg_catalog.int4,(adst->>'currencyPolicy')::pg_catalog.text,(adst->>'kind')::pg_catalog.text)::public.payment_receipt_snapshot_v1)::public.payment_receipt_accepted_reserve_v1;
 encoded:=public.payment_receipt_receipt_bytes((v->>'receiptId')::pg_catalog.uuid,(v->>'eventId')::pg_catalog.uuid,
  v->>'tenant',(v->>'reservationId')::pg_catalog.uuid,(v->>'operationId')::pg_catalog.uuid,v->>'effectKind',
  (v->>'actionId')::pg_catalog.uuid,accepted,(v->>'postingId')::pg_catalog.uuid,
  (v->>'originalCommitReceiptId')::pg_catalog.uuid,(v->>'originalPostingId')::pg_catalog.uuid,
  (v->>'effectRecordedAtEpochMillis')::pg_catalog.numeric::pg_catalog.int8,
  (m->>'amount')::pg_catalog.numeric,m->>'currency',
  ROW((esrc->>'accountId')::pg_catalog.uuid,(esrc->>'enrollmentId')::pg_catalog.uuid,(esrc->>'contractId')::pg_catalog.uuid,(esrc->>'currency')::pg_catalog.text,(esrc->>'currencyScale')::pg_catalog.numeric::pg_catalog.int4,(esrc->>'currencyPolicy')::pg_catalog.text,(esrc->>'kind')::pg_catalog.text)::public.payment_receipt_snapshot_v1,ROW((edst->>'accountId')::pg_catalog.uuid,(edst->>'enrollmentId')::pg_catalog.uuid,(edst->>'contractId')::pg_catalog.uuid,(edst->>'currency')::pg_catalog.text,(edst->>'currencyScale')::pg_catalog.numeric::pg_catalog.int4,(edst->>'currencyPolicy')::pg_catalog.text,(edst->>'kind')::pg_catalog.text)::public.payment_receipt_snapshot_v1,
  m->>'sourceMinorUnits',m->>'destinationMinorUnits',(m->>'fromAccountId')::pg_catalog.uuid,
  (m->>'toAccountId')::pg_catalog.uuid,
  ROW((sb->>'bookedBefore')::pg_catalog.numeric,(sb->>'heldBefore')::pg_catalog.numeric,
      (sb->>'bookedAfter')::pg_catalog.numeric,(sb->>'heldAfter')::pg_catalog.numeric)::public.payment_receipt_balances_v1,
  ROW((db->>'bookedBefore')::pg_catalog.numeric,(db->>'heldBefore')::pg_catalog.numeric,
      (db->>'bookedAfter')::pg_catalog.numeric,(db->>'heldAfter')::pg_catalog.numeric)::public.payment_receipt_balances_v1,
  actor->>'issuer',actor->>'subject');
 PERFORM public.payment_receipt_require(((form->>'encoding') COLLATE "C")=('FND_MONETARY_RECEIPT_V1' COLLATE "C")
  AND ((form->>'canonical') COLLATE "C")=(public.payment_receipt_b64(encoded,65536) COLLATE "C")
  AND ((form->>'digest') COLLATE "C")=(pg_catalog.encode(pg_catalog.sha256(encoded),'hex') COLLATE "C"));
 RETURN TRUE;
END
$bridge$;

CREATE FUNCTION public.payment_receipt_row_bound(e pg_catalog.jsonb,tenant pg_catalog.varchar,payment pg_catalog.uuid,
 source pg_catalog.uuid,destination pg_catalog.uuid,action pg_catalog.uuid,kind pg_catalog.varchar,receipt pg_catalog.uuid)
 RETURNS pg_catalog.bool LANGUAGE SQL IMMUTABLE SECURITY INVOKER SET search_path=pg_catalog AS $summary$
 SELECT public.payment_receipt_envelope_bound($1) IS TRUE
  AND (($1#>>'{value,tenant}') COLLATE "C") IS NOT DISTINCT FROM ($2::text COLLATE "C")
  AND ($1#>>'{value,operationId}')::pg_catalog.uuid IS NOT DISTINCT FROM $3
  AND ($1#>>'{value,effect,source,accountId}')::pg_catalog.uuid IS NOT DISTINCT FROM $4
  AND ($1#>>'{value,effect,destination,accountId}')::pg_catalog.uuid IS NOT DISTINCT FROM $5
  AND ($1#>>'{value,actionId}')::pg_catalog.uuid IS NOT DISTINCT FROM $6
  AND (($1#>>'{value,effectKind}') COLLATE "C") IS NOT DISTINCT FROM ($7::text COLLATE "C")
  AND ($1#>>'{value,receiptId}')::pg_catalog.uuid IS NOT DISTINCT FROM $8
$summary$;

REVOKE ALL ON TYPE public.payment_receipt_snapshot_v1 FROM PUBLIC;
GRANT USAGE ON TYPE public.payment_receipt_snapshot_v1 TO CURRENT_USER,PUBLIC;
REVOKE ALL ON TYPE public.payment_receipt_balances_v1 FROM PUBLIC;
GRANT USAGE ON TYPE public.payment_receipt_balances_v1 TO CURRENT_USER,PUBLIC;
REVOKE ALL ON TYPE public.payment_receipt_accepted_reserve_v1 FROM PUBLIC;
GRANT USAGE ON TYPE public.payment_receipt_accepted_reserve_v1 TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_require(pg_catalog.bool) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_require(pg_catalog.bool) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_utf16_length(pg_catalog.text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_utf16_length(pg_catalog.text) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_is_blank(pg_catalog.text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_is_blank(pg_catalog.text) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_text_valid(pg_catalog.text,pg_catalog.int4) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_text_valid(pg_catalog.text,pg_catalog.int4) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_lp_text(pg_catalog.text,pg_catalog.int4) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_lp_text(pg_catalog.text,pg_catalog.int4) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_b64(pg_catalog.bytea,pg_catalog.int4) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_b64(pg_catalog.bytea,pg_catalog.int4) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_numeric_text(pg_catalog.numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_numeric_text(pg_catalog.numeric) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_minor_text(pg_catalog.numeric,pg_catalog.int4) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_minor_text(pg_catalog.numeric,pg_catalog.int4) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_snapshot_bytes(public.payment_receipt_snapshot_v1) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_snapshot_bytes(public.payment_receipt_snapshot_v1) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_balances_bytes(public.payment_receipt_balances_v1) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_balances_bytes(public.payment_receipt_balances_v1) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_intent_bytes(pg_catalog.text,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.numeric,pg_catalog.text,pg_catalog.int4,pg_catalog.int8) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_intent_bytes(pg_catalog.text,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.numeric,pg_catalog.text,pg_catalog.int4,pg_catalog.int8) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_accepted_bytes(public.payment_receipt_accepted_reserve_v1) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_accepted_bytes(public.payment_receipt_accepted_reserve_v1) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_receipt_bytes(pg_catalog.uuid,pg_catalog.uuid,pg_catalog.text,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.text,pg_catalog.uuid,public.payment_receipt_accepted_reserve_v1,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.int8,pg_catalog.numeric,pg_catalog.text,public.payment_receipt_snapshot_v1,public.payment_receipt_snapshot_v1,pg_catalog.text,pg_catalog.text,pg_catalog.uuid,pg_catalog.uuid,public.payment_receipt_balances_v1,public.payment_receipt_balances_v1,pg_catalog.text,pg_catalog.text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_receipt_bytes(pg_catalog.uuid,pg_catalog.uuid,pg_catalog.text,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.text,pg_catalog.uuid,public.payment_receipt_accepted_reserve_v1,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.int8,pg_catalog.numeric,pg_catalog.text,public.payment_receipt_snapshot_v1,public.payment_receipt_snapshot_v1,pg_catalog.text,pg_catalog.text,pg_catalog.uuid,pg_catalog.uuid,public.payment_receipt_balances_v1,public.payment_receipt_balances_v1,pg_catalog.text,pg_catalog.text) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_envelope_bound(pg_catalog.jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_envelope_bound(pg_catalog.jsonb) TO CURRENT_USER,PUBLIC;
REVOKE ALL ON FUNCTION public.payment_receipt_row_bound(pg_catalog.jsonb,pg_catalog.varchar,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.varchar,pg_catalog.uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.payment_receipt_row_bound(pg_catalog.jsonb,pg_catalog.varchar,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.uuid,pg_catalog.varchar,pg_catalog.uuid) TO CURRENT_USER,PUBLIC;

ALTER TABLE public.managed_payment_monetary_proof
 ADD CONSTRAINT managed_monetary_canonical_value CHECK(public.payment_receipt_row_bound(
  receipt_envelope,tenant_id,payment_id,source_account_id,destination_account_id,
  participant_action_id,kind,receipt_id) IS TRUE);

CREATE OR REPLACE FUNCTION public.managed_payment_repository_runtime() RETURNS boolean
 LANGUAGE SQL VOLATILE SECURITY INVOKER SET search_path=pg_catalog,public,pg_temp AS $repository$
 WITH expected_functions(name,args,names,result,language,source,defaults,default_expression) AS (VALUES
 ('payment_receipt_require',ARRAY['pg_catalog.bool']::pg_catalog.text[],ARRAY['valid']::pg_catalog.text[],'pg_catalog.void','plpgsql','f86230b3aee3f04810cce11fc1efd76dc025842c21bca7ee35a52af536084d0b',0,''),
 ('payment_receipt_utf16_length',ARRAY['pg_catalog.text']::pg_catalog.text[],ARRAY['value']::pg_catalog.text[],'pg_catalog.int8','sql','7252a22567b8019b6586ed64335bd20ffd4026471aa5d4d9731ad4faefc65686',0,''),
 ('payment_receipt_is_blank',ARRAY['pg_catalog.text']::pg_catalog.text[],ARRAY['value']::pg_catalog.text[],'pg_catalog.bool','sql','72f5838b239a93bb39654860aa8ad0f76a04d3656eb975873f89041647969742',0,''),
 ('payment_receipt_text_valid',ARRAY['pg_catalog.text','pg_catalog.int4']::pg_catalog.text[],ARRAY['value','max_bytes']::pg_catalog.text[],'pg_catalog.bool','plpgsql','55bd70900779ff69e1b943f00df8280d155a9573f2d64d60781e339907a2f8c3',0,''),
 ('payment_receipt_lp_text',ARRAY['pg_catalog.text','pg_catalog.int4']::pg_catalog.text[],ARRAY['value','max_bytes']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','c16cd5fd38949993d675f8f245a1f082cb53f8f75575e178e75c68446a5fa89f',1,'16384'),
 ('payment_receipt_b64',ARRAY['pg_catalog.bytea','pg_catalog.int4']::pg_catalog.text[],ARRAY['value','max_encoded']::pg_catalog.text[],'pg_catalog.text','plpgsql','584eeacc9bac89c376f7ed82cc971829e2037a1cba778ff72da7cb2335094094',1,'65536'),
 ('payment_receipt_numeric_text',ARRAY['pg_catalog.numeric']::pg_catalog.text[],ARRAY['value']::pg_catalog.text[],'pg_catalog.text','plpgsql','36479e79e1713bbc07e9144aa7039288bb9783bbd5dc212f2fc4047f6ef568ec',0,''),
 ('payment_receipt_minor_text',ARRAY['pg_catalog.numeric','pg_catalog.int4']::pg_catalog.text[],ARRAY['value','actual_scale']::pg_catalog.text[],'pg_catalog.text','plpgsql','38e8f5687d229e339c818881216b8cca403640821209d1517f29769e41a8c79f',0,''),
 ('payment_receipt_snapshot_bytes',ARRAY['public.payment_receipt_snapshot_v1']::pg_catalog.text[],ARRAY['s']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','4f5306719dce95c340298ef9aecb53b85b2131efb59495c70e4f8336a165df2b',0,''),
 ('payment_receipt_balances_bytes',ARRAY['public.payment_receipt_balances_v1']::pg_catalog.text[],ARRAY['b']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','918568e31f31b2b2d3d7adf0a048843bc4774b43f271e05efc178b25a07e9090',0,''),
 ('payment_receipt_intent_bytes',ARRAY['pg_catalog.text','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.numeric','pg_catalog.text','pg_catalog.int4','pg_catalog.int8']::pg_catalog.text[],ARRAY['tenant','operation','source','destination','amount','currency','ttl','deadline']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','9e9fde7f4ee2c6ea6a2e25f88333b481b6d86bb608b175c4642670958e29dfb2',0,''),
 ('payment_receipt_accepted_bytes',ARRAY['public.payment_receipt_accepted_reserve_v1']::pg_catalog.text[],ARRAY['a']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','70d34cf18a91abcdd096873c3075c476e6247f8f95bc1537b2d6eae017c0de26',0,''),
 ('payment_receipt_receipt_bytes',ARRAY['pg_catalog.uuid','pg_catalog.uuid','pg_catalog.text','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.text','pg_catalog.uuid','public.payment_receipt_accepted_reserve_v1','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.int8','pg_catalog.numeric','pg_catalog.text','public.payment_receipt_snapshot_v1','public.payment_receipt_snapshot_v1','pg_catalog.text','pg_catalog.text','pg_catalog.uuid','pg_catalog.uuid','public.payment_receipt_balances_v1','public.payment_receipt_balances_v1','pg_catalog.text','pg_catalog.text']::pg_catalog.text[],ARRAY['receipt','event','tenant','reservation','operation','kind','action','a','posting','original_receipt','original_posting','recorded','amount','currency','source_snapshot','destination_snapshot','source_minor','destination_minor','flow_from','flow_to','sb','db','issuer','subject']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','4287728696ca31ee3acaddc8bcb51b4fa930bd69fa45da606d0fe53d39695110',0,''),
 ('payment_receipt_envelope_bound',ARRAY['pg_catalog.jsonb']::pg_catalog.text[],ARRAY['e']::pg_catalog.text[],'pg_catalog.bool','plpgsql','5747b2326a4f98db3c3b1bd426f9b50b235c0aad3ab2fb29df75d6dedc278755',0,''),
 ('payment_receipt_row_bound',ARRAY['pg_catalog.jsonb','pg_catalog.varchar','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.varchar','pg_catalog.uuid']::pg_catalog.text[],ARRAY['e','tenant','payment','source','destination','action','kind','receipt']::pg_catalog.text[],'pg_catalog.bool','sql','4ca5c4a202ebbb423bfdebf9f2062968ee584f05c5ebec2517c38bfbd31ac234',0,'')), expected_types(name,names,fields) AS (VALUES
 ('payment_receipt_snapshot_v1',ARRAY['account_id','enrollment_id','contract_id','currency','currency_scale','currency_policy','kind']::pg_catalog.text[],ARRAY['pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.text','pg_catalog.int4','pg_catalog.text','pg_catalog.text']::pg_catalog.text[]),
 ('payment_receipt_balances_v1',ARRAY['booked_before','held_before','booked_after','held_after']::pg_catalog.text[],ARRAY['pg_catalog.numeric','pg_catalog.numeric','pg_catalog.numeric','pg_catalog.numeric']::pg_catalog.text[]),
 ('payment_receipt_accepted_reserve_v1',ARRAY['tenant','reservation_id','operation_id','source_account_id','destination_account_id','amount','currency','ttl_seconds','not_after_epoch_millis','intent_recorded_at_epoch_millis','expires_at_epoch_millis','source_snapshot','destination_snapshot']::pg_catalog.text[],ARRAY['pg_catalog.text','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.numeric','pg_catalog.text','pg_catalog.int4','pg_catalog.int8','pg_catalog.int8','pg_catalog.int8','public.payment_receipt_snapshot_v1','public.payment_receipt_snapshot_v1']::pg_catalog.text[])), proof AS (
 SELECT c.oid,c.relowner FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname='managed_payment_monetary_proof' AND c.relkind='r'
), helper_functions AS (
 SELECT p.*,l.lanname,rn.nspname||'.'||rt.typname AS result,
  ARRAY(SELECT an.nspname||'.'||at.typname FROM pg_catalog.unnest(p.proargtypes) WITH ORDINALITY a(type,number)
   JOIN pg_catalog.pg_type at ON at.oid=a.type JOIN pg_catalog.pg_namespace an ON an.oid=at.typnamespace
   ORDER BY a.number) AS args
 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
 JOIN pg_catalog.pg_language l ON l.oid=p.prolang
 JOIN pg_catalog.pg_type rt ON rt.oid=p.prorettype JOIN pg_catalog.pg_namespace rn ON rn.oid=rt.typnamespace
 WHERE n.nspname='public' AND p.proname=ANY(ARRAY['payment_receipt_require','payment_receipt_utf16_length','payment_receipt_is_blank','payment_receipt_text_valid','payment_receipt_lp_text','payment_receipt_b64','payment_receipt_numeric_text','payment_receipt_minor_text','payment_receipt_snapshot_bytes','payment_receipt_balances_bytes','payment_receipt_intent_bytes','payment_receipt_accepted_bytes','payment_receipt_receipt_bytes','payment_receipt_envelope_bound','payment_receipt_row_bound']::pg_catalog.text[])
), helper_types AS (
 SELECT t.*,ARRAY(SELECT a.attname::text FROM pg_catalog.pg_attribute a WHERE a.attrelid=t.typrelid
  AND a.attnum>0 ORDER BY a.attnum) AS names,
 ARRAY(SELECT n.nspname||'.'||at.typname FROM pg_catalog.pg_attribute a
  JOIN pg_catalog.pg_type at ON at.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=at.typnamespace
  WHERE a.attrelid=t.typrelid AND a.attnum>0 ORDER BY a.attnum) AS fields
 FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
 JOIN pg_catalog.pg_class c ON c.oid=t.typrelid AND c.relkind='c' AND c.reltype=t.oid
  AND c.relowner=t.typowner AND c.relnamespace=t.typnamespace AND c.relacl IS NULL
 WHERE n.nspname='public' AND t.typname=ANY(ARRAY['payment_receipt_snapshot_v1','payment_receipt_balances_v1','payment_receipt_accepted_reserve_v1']::pg_catalog.text[])
), canonical_metadata AS (
 SELECT (SELECT count(*)=1 FROM proof)
 AND (SELECT count(*)=15 FROM helper_functions)
 AND (SELECT count(*)=15 AND bool_and((
  p.args=e.args AND p.proargnames=e.names AND p.result=e.result AND p.lanname=e.language
  AND p.proowner=proof.relowner AND p.provolatile='i' AND NOT p.prosecdef AND NOT p.proretset
  AND p.prokind='f' AND p.provariadic=0 AND NOT p.proleakproof AND p.prosupport=0
  AND NOT p.proisstrict AND p.proparallel='u' AND p.proargmodes IS NULL AND p.proallargtypes IS NULL
  AND p.probin IS NULL AND p.prosqlbody IS NULL AND p.protrftypes IS NULL
  AND p.proconfig=ARRAY['search_path=pg_catalog'] AND p.pronargdefaults=e.defaults
  AND COALESCE(pg_catalog.pg_get_expr(p.proargdefaults,0),'')=e.default_expression
  AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p.prosrc,'UTF8')),'hex')=e.source
  AND (SELECT count(*)=2 AND bool_and(a.grantee IN(0,proof.relowner) AND a.grantor=proof.relowner
   AND a.privilege_type='EXECUTE' AND NOT a.is_grantable)
   FROM pg_catalog.aclexplode(COALESCE(p.proacl,pg_catalog.acldefault('f',p.proowner))) a)) IS TRUE)
  FROM helper_functions p JOIN expected_functions e ON e.name=p.proname CROSS JOIN proof)
 AND (SELECT count(*)=3 FROM helper_types)
 AND (SELECT count(*)=3 AND bool_and(t.typowner=proof.relowner AND t.typtype='c'
  AND t.names=e.names AND t.fields=e.fields
  AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_attribute a WHERE a.attrelid=t.typrelid AND a.attnum>0
   AND (a.attisdropped OR a.atttypmod<>-1 OR a.attnotnull OR a.attgenerated<>'' OR a.attidentity<>''))
  AND EXISTS(SELECT 1 FROM pg_catalog.pg_type ar JOIN pg_catalog.pg_namespace n ON n.oid=ar.typnamespace
   WHERE ar.oid=t.typarray AND ar.typelem=t.oid AND ar.typowner=proof.relowner
    AND n.nspname='public' AND ar.typname='_'||t.typname AND ar.typacl IS NULL)
  AND (SELECT count(*)=2 AND bool_and(a.grantee IN(0,proof.relowner) AND a.grantor=proof.relowner
   AND a.privilege_type='USAGE' AND NOT a.is_grantable)
   FROM pg_catalog.aclexplode(COALESCE(t.typacl,pg_catalog.acldefault('T',t.typowner))) a))
  FROM helper_types t JOIN expected_types e ON e.name=t.typname CROSS JOIN proof)
 AND (SELECT pg_catalog.count(*)=15 AND pg_catalog.bool_and(COALESCE(
 a.attname=e.name AND n.nspname='pg_catalog' AND t.typname=e.type AND a.atttypmod=e.modifier
 AND a.attnotnull AND NOT a.attisdropped AND a.attgenerated='' AND a.attidentity='',false))
 FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
 JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
 FULL JOIN (VALUES
  (1,'tenant_id','varchar',68),
  (2,'payment_id','uuid',-1),
  (3,'source_account_id','uuid',-1),
  (4,'destination_account_id','uuid',-1),
  (5,'effect_id','uuid',-1),
  (6,'participant_action_id','uuid',-1),
  (7,'kind','varchar',20),
  (8,'receipt_id','uuid',-1),
  (9,'retained_version','int8',-1),
  (10,'capture_fence','int8',-1),
  (11,'observed_reservation_version','int8',-1),
  (12,'observed_at','timestamptz',-1),
  (13,'observer_subject','varchar',259),
  (14,'receipt_envelope','jsonb',-1),
  (15,'retained_at','timestamptz',-1)) e(number,name,type,modifier) ON e.number=a.attnum
 WHERE a.attrelid='public.managed_payment_monetary_proof'::pg_catalog.regclass AND a.attnum>0)
 AND EXISTS(SELECT 1 FROM pg_catalog.pg_constraint c CROSS JOIN proof
  JOIN helper_functions f ON f.proname='payment_receipt_row_bound'
  WHERE c.conrelid=proof.oid AND c.conname='managed_monetary_canonical_value' AND c.contype='c'
   AND c.connamespace='public'::pg_catalog.regnamespace AND c.convalidated AND NOT c.connoinherit
   AND c.conislocal AND c.coninhcount=0 AND c.conparentid=0
   AND c.conkey=ARRAY[14,1,2,3,4,6,7,8]::pg_catalog.int2[]
   AND pg_catalog.pg_get_expr(c.conbin,c.conrelid,false) IN('(payment_receipt_row_bound(receipt_envelope, tenant_id, payment_id, source_account_id, destination_account_id, participant_action_id, kind, receipt_id) IS TRUE)',
    '(public.payment_receipt_row_bound(receipt_envelope, tenant_id, payment_id, source_account_id, destination_account_id, participant_action_id, kind, receipt_id) IS TRUE)')
   AND EXISTS(SELECT 1 FROM pg_catalog.pg_depend d WHERE d.classid='pg_catalog.pg_constraint'::pg_catalog.regclass
    AND d.objid=c.oid AND d.refclassid='pg_catalog.pg_proc'::pg_catalog.regclass AND d.refobjid=f.oid
    AND d.deptype='n')
   AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_depend d JOIN pg_catalog.pg_proc p ON p.oid=d.refobjid
    JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
    WHERE d.classid='pg_catalog.pg_constraint'::pg_catalog.regclass AND d.objid=c.oid
     AND d.refclassid='pg_catalog.pg_proc'::pg_catalog.regclass AND n.nspname<>'pg_catalog' AND p.oid<>f.oid))
 AS allowed)
, predecessor AS (
WITH protected(name) AS (VALUES ('managed_payment_operation'),('managed_payment_action'),('managed_payment_effect'), ('managed_payment_projection'),('managed_payment_history'),('managed_payment_outbox'),('managed_payment_account_gate'), ('managed_payment_account_fence'),('managed_payment_account_action'),('managed_payment_account_history'),('managed_payment_account_outbox'), ('managed_payment_account_observation'),('managed_payment_account_observation_member'),('managed_payment_account_observation_reference'),('managed_payment_monetary_proof')), relations AS (SELECT c.* FROM protected p JOIN pg_catalog.pg_class c ON c.relname=p.name JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public') SELECT NOT r.rolsuper AND NOT r.rolbypassrls AND NOT pg_catalog.has_database_privilege(current_user,current_database(),'CREATE') AND NOT pg_catalog.has_database_privilege(current_user,current_database(),'TEMP') AND NOT r.rolcreaterole AND NOT r.rolcreatedb AND NOT has_schema_privilege(current_user,'public','CREATE') AND NOT pg_has_role(current_user,'payment_closure_guard','MEMBER') AND (SELECT count(*)=15 AND bool_and(relrowsecurity AND relforcerowsecurity AND NOT pg_has_role(current_user,relowner,'MEMBER')) FROM relations) AND EXISTS(SELECT 1 FROM pg_catalog.pg_roles g WHERE g.rolname='payment_closure_guard' AND NOT g.rolsuper AND NOT g.rolbypassrls AND NOT g.rolcanlogin AND NOT g.rolcreaterole AND NOT g.rolcreatedb AND NOT g.rolinherit) AND EXISTS(SELECT 1 FROM pg_catalog.pg_proc p WHERE p.oid='public.managed_payment_account_capture(text,uuid,uuid,uuid)'::regprocedure AND p.prosecdef AND p.proowner='payment_closure_guard'::regrole AND p.proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp']) AS allowed FROM pg_catalog.pg_roles r WHERE r.rolname=current_user
 ) SELECT COALESCE((SELECT allowed FROM predecessor),false)
  AND COALESCE((SELECT allowed FROM canonical_metadata),false)
$repository$;

DO $installed$
BEGIN
 IF (WITH expected_functions(name,args,names,result,language,source,defaults,default_expression) AS (VALUES
 ('payment_receipt_require',ARRAY['pg_catalog.bool']::pg_catalog.text[],ARRAY['valid']::pg_catalog.text[],'pg_catalog.void','plpgsql','f86230b3aee3f04810cce11fc1efd76dc025842c21bca7ee35a52af536084d0b',0,''),
 ('payment_receipt_utf16_length',ARRAY['pg_catalog.text']::pg_catalog.text[],ARRAY['value']::pg_catalog.text[],'pg_catalog.int8','sql','7252a22567b8019b6586ed64335bd20ffd4026471aa5d4d9731ad4faefc65686',0,''),
 ('payment_receipt_is_blank',ARRAY['pg_catalog.text']::pg_catalog.text[],ARRAY['value']::pg_catalog.text[],'pg_catalog.bool','sql','72f5838b239a93bb39654860aa8ad0f76a04d3656eb975873f89041647969742',0,''),
 ('payment_receipt_text_valid',ARRAY['pg_catalog.text','pg_catalog.int4']::pg_catalog.text[],ARRAY['value','max_bytes']::pg_catalog.text[],'pg_catalog.bool','plpgsql','55bd70900779ff69e1b943f00df8280d155a9573f2d64d60781e339907a2f8c3',0,''),
 ('payment_receipt_lp_text',ARRAY['pg_catalog.text','pg_catalog.int4']::pg_catalog.text[],ARRAY['value','max_bytes']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','c16cd5fd38949993d675f8f245a1f082cb53f8f75575e178e75c68446a5fa89f',1,'16384'),
 ('payment_receipt_b64',ARRAY['pg_catalog.bytea','pg_catalog.int4']::pg_catalog.text[],ARRAY['value','max_encoded']::pg_catalog.text[],'pg_catalog.text','plpgsql','584eeacc9bac89c376f7ed82cc971829e2037a1cba778ff72da7cb2335094094',1,'65536'),
 ('payment_receipt_numeric_text',ARRAY['pg_catalog.numeric']::pg_catalog.text[],ARRAY['value']::pg_catalog.text[],'pg_catalog.text','plpgsql','36479e79e1713bbc07e9144aa7039288bb9783bbd5dc212f2fc4047f6ef568ec',0,''),
 ('payment_receipt_minor_text',ARRAY['pg_catalog.numeric','pg_catalog.int4']::pg_catalog.text[],ARRAY['value','actual_scale']::pg_catalog.text[],'pg_catalog.text','plpgsql','38e8f5687d229e339c818881216b8cca403640821209d1517f29769e41a8c79f',0,''),
 ('payment_receipt_snapshot_bytes',ARRAY['public.payment_receipt_snapshot_v1']::pg_catalog.text[],ARRAY['s']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','4f5306719dce95c340298ef9aecb53b85b2131efb59495c70e4f8336a165df2b',0,''),
 ('payment_receipt_balances_bytes',ARRAY['public.payment_receipt_balances_v1']::pg_catalog.text[],ARRAY['b']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','918568e31f31b2b2d3d7adf0a048843bc4774b43f271e05efc178b25a07e9090',0,''),
 ('payment_receipt_intent_bytes',ARRAY['pg_catalog.text','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.numeric','pg_catalog.text','pg_catalog.int4','pg_catalog.int8']::pg_catalog.text[],ARRAY['tenant','operation','source','destination','amount','currency','ttl','deadline']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','9e9fde7f4ee2c6ea6a2e25f88333b481b6d86bb608b175c4642670958e29dfb2',0,''),
 ('payment_receipt_accepted_bytes',ARRAY['public.payment_receipt_accepted_reserve_v1']::pg_catalog.text[],ARRAY['a']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','70d34cf18a91abcdd096873c3075c476e6247f8f95bc1537b2d6eae017c0de26',0,''),
 ('payment_receipt_receipt_bytes',ARRAY['pg_catalog.uuid','pg_catalog.uuid','pg_catalog.text','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.text','pg_catalog.uuid','public.payment_receipt_accepted_reserve_v1','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.int8','pg_catalog.numeric','pg_catalog.text','public.payment_receipt_snapshot_v1','public.payment_receipt_snapshot_v1','pg_catalog.text','pg_catalog.text','pg_catalog.uuid','pg_catalog.uuid','public.payment_receipt_balances_v1','public.payment_receipt_balances_v1','pg_catalog.text','pg_catalog.text']::pg_catalog.text[],ARRAY['receipt','event','tenant','reservation','operation','kind','action','a','posting','original_receipt','original_posting','recorded','amount','currency','source_snapshot','destination_snapshot','source_minor','destination_minor','flow_from','flow_to','sb','db','issuer','subject']::pg_catalog.text[],'pg_catalog.bytea','plpgsql','4287728696ca31ee3acaddc8bcb51b4fa930bd69fa45da606d0fe53d39695110',0,''),
 ('payment_receipt_envelope_bound',ARRAY['pg_catalog.jsonb']::pg_catalog.text[],ARRAY['e']::pg_catalog.text[],'pg_catalog.bool','plpgsql','5747b2326a4f98db3c3b1bd426f9b50b235c0aad3ab2fb29df75d6dedc278755',0,''),
 ('payment_receipt_row_bound',ARRAY['pg_catalog.jsonb','pg_catalog.varchar','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.varchar','pg_catalog.uuid']::pg_catalog.text[],ARRAY['e','tenant','payment','source','destination','action','kind','receipt']::pg_catalog.text[],'pg_catalog.bool','sql','4ca5c4a202ebbb423bfdebf9f2062968ee584f05c5ebec2517c38bfbd31ac234',0,'')), expected_types(name,names,fields) AS (VALUES
 ('payment_receipt_snapshot_v1',ARRAY['account_id','enrollment_id','contract_id','currency','currency_scale','currency_policy','kind']::pg_catalog.text[],ARRAY['pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.text','pg_catalog.int4','pg_catalog.text','pg_catalog.text']::pg_catalog.text[]),
 ('payment_receipt_balances_v1',ARRAY['booked_before','held_before','booked_after','held_after']::pg_catalog.text[],ARRAY['pg_catalog.numeric','pg_catalog.numeric','pg_catalog.numeric','pg_catalog.numeric']::pg_catalog.text[]),
 ('payment_receipt_accepted_reserve_v1',ARRAY['tenant','reservation_id','operation_id','source_account_id','destination_account_id','amount','currency','ttl_seconds','not_after_epoch_millis','intent_recorded_at_epoch_millis','expires_at_epoch_millis','source_snapshot','destination_snapshot']::pg_catalog.text[],ARRAY['pg_catalog.text','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.uuid','pg_catalog.numeric','pg_catalog.text','pg_catalog.int4','pg_catalog.int8','pg_catalog.int8','pg_catalog.int8','public.payment_receipt_snapshot_v1','public.payment_receipt_snapshot_v1']::pg_catalog.text[])), proof AS (
 SELECT c.oid,c.relowner FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname='managed_payment_monetary_proof' AND c.relkind='r'
), helper_functions AS (
 SELECT p.*,l.lanname,rn.nspname||'.'||rt.typname AS result,
  ARRAY(SELECT an.nspname||'.'||at.typname FROM pg_catalog.unnest(p.proargtypes) WITH ORDINALITY a(type,number)
   JOIN pg_catalog.pg_type at ON at.oid=a.type JOIN pg_catalog.pg_namespace an ON an.oid=at.typnamespace
   ORDER BY a.number) AS args
 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
 JOIN pg_catalog.pg_language l ON l.oid=p.prolang
 JOIN pg_catalog.pg_type rt ON rt.oid=p.prorettype JOIN pg_catalog.pg_namespace rn ON rn.oid=rt.typnamespace
 WHERE n.nspname='public' AND p.proname=ANY(ARRAY['payment_receipt_require','payment_receipt_utf16_length','payment_receipt_is_blank','payment_receipt_text_valid','payment_receipt_lp_text','payment_receipt_b64','payment_receipt_numeric_text','payment_receipt_minor_text','payment_receipt_snapshot_bytes','payment_receipt_balances_bytes','payment_receipt_intent_bytes','payment_receipt_accepted_bytes','payment_receipt_receipt_bytes','payment_receipt_envelope_bound','payment_receipt_row_bound']::pg_catalog.text[])
), helper_types AS (
 SELECT t.*,ARRAY(SELECT a.attname::text FROM pg_catalog.pg_attribute a WHERE a.attrelid=t.typrelid
  AND a.attnum>0 ORDER BY a.attnum) AS names,
 ARRAY(SELECT n.nspname||'.'||at.typname FROM pg_catalog.pg_attribute a
  JOIN pg_catalog.pg_type at ON at.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=at.typnamespace
  WHERE a.attrelid=t.typrelid AND a.attnum>0 ORDER BY a.attnum) AS fields
 FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
 JOIN pg_catalog.pg_class c ON c.oid=t.typrelid AND c.relkind='c' AND c.reltype=t.oid
  AND c.relowner=t.typowner AND c.relnamespace=t.typnamespace AND c.relacl IS NULL
 WHERE n.nspname='public' AND t.typname=ANY(ARRAY['payment_receipt_snapshot_v1','payment_receipt_balances_v1','payment_receipt_accepted_reserve_v1']::pg_catalog.text[])
), canonical_metadata AS (
 SELECT (SELECT count(*)=1 FROM proof)
 AND (SELECT count(*)=15 FROM helper_functions)
 AND (SELECT count(*)=15 AND bool_and((
  p.args=e.args AND p.proargnames=e.names AND p.result=e.result AND p.lanname=e.language
  AND p.proowner=proof.relowner AND p.provolatile='i' AND NOT p.prosecdef AND NOT p.proretset
  AND p.prokind='f' AND p.provariadic=0 AND NOT p.proleakproof AND p.prosupport=0
  AND NOT p.proisstrict AND p.proparallel='u' AND p.proargmodes IS NULL AND p.proallargtypes IS NULL
  AND p.probin IS NULL AND p.prosqlbody IS NULL AND p.protrftypes IS NULL
  AND p.proconfig=ARRAY['search_path=pg_catalog'] AND p.pronargdefaults=e.defaults
  AND COALESCE(pg_catalog.pg_get_expr(p.proargdefaults,0),'')=e.default_expression
  AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p.prosrc,'UTF8')),'hex')=e.source
  AND (SELECT count(*)=2 AND bool_and(a.grantee IN(0,proof.relowner) AND a.grantor=proof.relowner
   AND a.privilege_type='EXECUTE' AND NOT a.is_grantable)
   FROM pg_catalog.aclexplode(COALESCE(p.proacl,pg_catalog.acldefault('f',p.proowner))) a)) IS TRUE)
  FROM helper_functions p JOIN expected_functions e ON e.name=p.proname CROSS JOIN proof)
 AND (SELECT count(*)=3 FROM helper_types)
 AND (SELECT count(*)=3 AND bool_and(t.typowner=proof.relowner AND t.typtype='c'
  AND t.names=e.names AND t.fields=e.fields
  AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_attribute a WHERE a.attrelid=t.typrelid AND a.attnum>0
   AND (a.attisdropped OR a.atttypmod<>-1 OR a.attnotnull OR a.attgenerated<>'' OR a.attidentity<>''))
  AND EXISTS(SELECT 1 FROM pg_catalog.pg_type ar JOIN pg_catalog.pg_namespace n ON n.oid=ar.typnamespace
   WHERE ar.oid=t.typarray AND ar.typelem=t.oid AND ar.typowner=proof.relowner
    AND n.nspname='public' AND ar.typname='_'||t.typname AND ar.typacl IS NULL)
  AND (SELECT count(*)=2 AND bool_and(a.grantee IN(0,proof.relowner) AND a.grantor=proof.relowner
   AND a.privilege_type='USAGE' AND NOT a.is_grantable)
   FROM pg_catalog.aclexplode(COALESCE(t.typacl,pg_catalog.acldefault('T',t.typowner))) a))
  FROM helper_types t JOIN expected_types e ON e.name=t.typname CROSS JOIN proof)
 AND (SELECT pg_catalog.count(*)=15 AND pg_catalog.bool_and(COALESCE(
 a.attname=e.name AND n.nspname='pg_catalog' AND t.typname=e.type AND a.atttypmod=e.modifier
 AND a.attnotnull AND NOT a.attisdropped AND a.attgenerated='' AND a.attidentity='',false))
 FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
 JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
 FULL JOIN (VALUES
  (1,'tenant_id','varchar',68),
  (2,'payment_id','uuid',-1),
  (3,'source_account_id','uuid',-1),
  (4,'destination_account_id','uuid',-1),
  (5,'effect_id','uuid',-1),
  (6,'participant_action_id','uuid',-1),
  (7,'kind','varchar',20),
  (8,'receipt_id','uuid',-1),
  (9,'retained_version','int8',-1),
  (10,'capture_fence','int8',-1),
  (11,'observed_reservation_version','int8',-1),
  (12,'observed_at','timestamptz',-1),
  (13,'observer_subject','varchar',259),
  (14,'receipt_envelope','jsonb',-1),
  (15,'retained_at','timestamptz',-1)) e(number,name,type,modifier) ON e.number=a.attnum
 WHERE a.attrelid='public.managed_payment_monetary_proof'::pg_catalog.regclass AND a.attnum>0)
 AND EXISTS(SELECT 1 FROM pg_catalog.pg_constraint c CROSS JOIN proof
  JOIN helper_functions f ON f.proname='payment_receipt_row_bound'
  WHERE c.conrelid=proof.oid AND c.conname='managed_monetary_canonical_value' AND c.contype='c'
   AND c.connamespace='public'::pg_catalog.regnamespace AND c.convalidated AND NOT c.connoinherit
   AND c.conislocal AND c.coninhcount=0 AND c.conparentid=0
   AND c.conkey=ARRAY[14,1,2,3,4,6,7,8]::pg_catalog.int2[]
   AND pg_catalog.pg_get_expr(c.conbin,c.conrelid,false) IN('(payment_receipt_row_bound(receipt_envelope, tenant_id, payment_id, source_account_id, destination_account_id, participant_action_id, kind, receipt_id) IS TRUE)',
    '(public.payment_receipt_row_bound(receipt_envelope, tenant_id, payment_id, source_account_id, destination_account_id, participant_action_id, kind, receipt_id) IS TRUE)')
   AND EXISTS(SELECT 1 FROM pg_catalog.pg_depend d WHERE d.classid='pg_catalog.pg_constraint'::pg_catalog.regclass
    AND d.objid=c.oid AND d.refclassid='pg_catalog.pg_proc'::pg_catalog.regclass AND d.refobjid=f.oid
    AND d.deptype='n')
   AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_depend d JOIN pg_catalog.pg_proc p ON p.oid=d.refobjid
    JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
    WHERE d.classid='pg_catalog.pg_constraint'::pg_catalog.regclass AND d.objid=c.oid
     AND d.refclassid='pg_catalog.pg_proc'::pg_catalog.regclass AND n.nspname<>'pg_catalog' AND p.oid<>f.oid))
 AS allowed)
 SELECT allowed FROM canonical_metadata) IS NOT TRUE THEN
  RAISE EXCEPTION 'PAYMENT_CANONICAL_INSTALLATION_INVALID' USING ERRCODE='23514';
 END IF;
END
$installed$;
