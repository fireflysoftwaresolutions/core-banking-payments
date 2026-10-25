#!/usr/bin/env python3
"""Qualify the bounded payment-core protocol against actual payments/accounts/ledger JARs.

Only synthetic loopback services and newly owned disposable PostgreSQL containers are used.
The accepted funds qualifier supplies common private issuer/database helpers; its identity and
all executable identities are recorded. This is a three-core gate, not experience/domain or
production acceptance. Financial observations execute the genuine ledger SDK inside payments.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import http.server
import importlib.util
import json
import os
import pathlib
import re
import secrets
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile

SCOPES=['payments:create','payments:read','payments:coordinate','payments:confirm','payments:cancel','payments:recover',
        'funds:read','funds:reserve','funds:commit','funds:release','funds:reconcile','funds:expire']
BASE='/api/v1/tenants/owned/internal-payments'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for service in ['accounts','ledger','payments']:
        parser.add_argument('--'+service+'-jar',type=pathlib.Path,required=True)
    parser.add_argument('--payments-sdk',type=pathlib.Path,required=True)
    parser.add_argument('--sdk-probe-source',type=pathlib.Path,default=pathlib.Path(__file__).with_name('ManagedPaymentsSdkProbe.java'))
    parser.add_argument('--funds-fixture-script',type=pathlib.Path,required=True)
    parser.add_argument('--output-dir',type=pathlib.Path,required=True)
    parser.add_argument('--java',default=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in os.environ else shutil.which('java'))
    parser.add_argument('--docker-host',default=os.environ.get('DOCKER_HOST'))
    args=parser.parse_args()
    if not args.java or not shutil.which('docker'):parser.error('JDK25 and Docker are required')
    for path in [args.accounts_jar,args.ledger_jar,args.payments_jar,args.payments_sdk,args.sdk_probe_source,args.funds_fixture_script]:
        if not path.is_file():parser.error('Missing owned input: '+str(path))
    spec=importlib.util.spec_from_file_location('owned_funds_fixture',args.funds_fixture_script)
    f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
    f.ROOT=args.output_dir.resolve();f.ROOT.mkdir(parents=True,exist_ok=True);(f.ROOT/'reports').mkdir(exist_ok=True)
    f.JAVA=args.java;f.DOCKER=[shutil.which('docker')]+(['--host',args.docker_host] if args.docker_host else [])
    f.CONTAINERS={};f.DB_PORTS={};f.JARS={};f.KEY=None
    f.BOOTSTRAP_PASSWORD=secrets.token_urlsafe(24);f.RUNTIME_PASSWORD=secrets.token_urlsafe(24);f.CLIENT_SECRET=secrets.token_urlsafe(24)
    report={'status':'FAILED','scope':'three-core payment protocol with genuine payment SDK consumer; actual experience/domain pending',
            'qualifier_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'fixture_sha256':hashlib.sha256(args.funds_fixture_script.read_bytes()).hexdigest(),'artifacts':{}}
    fixture=None
    def payment(method,path='',body=None,accounts=(),expected=None,scopes=None,replica='payments'):
        token=f.token(['payments-managed','ledger-funds'],accounts,SCOPES if scopes is None else scopes)
        request=urllib.request.Request('http://127.0.0.1:'+str(f.PEER_PORTS[replica])+BASE+path,
            data=None if body is None else json.dumps(body).encode(),method=method,
            headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
        try:
            with urllib.request.urlopen(request,timeout=20) as response:status,raw=response.status,response.read()
        except urllib.error.HTTPError as error:status,raw=error.code,error.read()
        value=json.loads(raw) if raw else None
        if expected is not None:f.check(status==expected,method+' payment-core '+path+' status='+str(expected),(status,value))
        return status,value
    def boot_payments(replica='payments'):
        log=(f.ROOT/(replica+'-boot-'+str(time.time_ns())+'.log')).open('w');f.LOG_FILES.append(str(log.name))
        env=dict(os.environ,DB_HOST='127.0.0.1',DB_PORT=str(f.DB_PORTS['payments']),DB_NAME='fnd_payments',
            DB_USERNAME='funds_runtime',DB_PASSWORD=f.RUNTIME_PASSWORD,DB_SSL_MODE='disable',SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(f.PEER_PORTS[replica]),
            SPRING_FLYWAY_USER='postgres',SPRING_FLYWAY_PASSWORD=f.BOOTSTRAP_PASSWORD,FIREFLY_EDA_ENABLED='false',
            MANAGED_PAYMENTS_SECURITY_ISSUER=f.ISSUER,MANAGED_PAYMENTS_SECURITY_AUDIENCE='payments-managed',MANAGED_PAYMENTS_SECURITY_JWK_SET_URI=f.FIXTURE_URL+'/jwks',
            MANAGED_PAYMENTS_ALLOW_LOOPBACK_HTTP='true',MANAGED_PAYMENTS_DELEGATION_MODE='validated-multi-audience',MANAGED_PAYMENTS_CLAIM_LEASE_SECONDS='15',
            MANAGED_PAYMENTS_PEERS_LEDGER_BASE_URL='http://127.0.0.1:'+str(f.PEER_PORTS['ledger']),MANAGED_PAYMENTS_PEERS_LEDGER_AUDIENCE='ledger-funds',
            LOGGING_LEVEL_ROOT='WARN',LOGGING_LEVEL_IO_FIREFLYSOFTWARE='INFO',LOGGING_LEVEL_ORG_FIREFLYFRAMEWORK='WARN')
        process=subprocess.Popen([f.JAVA,'-jar',str(f.JARS['payments'])],env=env,stdout=log,stderr=subprocess.STDOUT);f.PROCESSES[replica]=(process,log)
        end=time.monotonic()+90
        while time.monotonic()<end:
            if process.poll() is not None:raise RuntimeError('payments exited; inspect '+str(log.name))
            try:urllib.request.urlopen('http://127.0.0.1:'+str(f.PEER_PORTS[replica])+'/v3/api-docs',timeout=1).close();return
            except (OSError,urllib.error.HTTPError):time.sleep(.2)
        raise RuntimeError('owned payments startup timeout')
    try:
        with tempfile.TemporaryDirectory(prefix='owned-payment-core-') as temporary:
            for service,path in [('accounts',args.accounts_jar),('ledger',args.ledger_jar),('payments',args.payments_jar)]:
                frozen=pathlib.Path(temporary)/(service+'.jar');shutil.copy2(path,frozen)
                sha=hashlib.sha256(frozen.read_bytes()).hexdigest()
                if sha!=hashlib.sha256(path.read_bytes()).hexdigest():raise RuntimeError('Input changed during copy: '+service)
                f.JARS[service]=frozen;report['artifacts'][service]={'path':str(path.resolve()),'sha256':sha}
            sdk=pathlib.Path(temporary)/'payments-sdk.jar';shutil.copy2(args.payments_sdk,sdk)
            report['artifacts']['payments_sdk']={'path':str(args.payments_sdk.resolve()),'sha256':hashlib.sha256(sdk.read_bytes()).hexdigest()}
            report['sdk_probe_sha256']=hashlib.sha256(args.sdk_probe_source.read_bytes()).hexdigest()
            deps=pathlib.Path(temporary)/'sdk-dependencies';deps.mkdir()
            with zipfile.ZipFile(f.JARS['payments']) as archive:
                for name in archive.namelist():
                    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                        (deps/pathlib.Path(name).name).write_bytes(archive.read(name))
            classpath=os.pathsep.join([str(sdk),str(deps/'*')])
            compile_log=f.ROOT/'payment-sdk-compile.log'
            with compile_log.open('w') as out:
                subprocess.run([str(pathlib.Path(args.java).with_name('javac')),'-cp',classpath,'-d',str(deps),str(args.sdk_probe_source)],stdout=out,stderr=subprocess.STDOUT,check=True)
            f.KEY=pathlib.Path(temporary)/'signing.pem';f.run(['openssl','genrsa','-out',str(f.KEY),'2048']);os.chmod(f.KEY,0o600)
            modulus=f.run(['openssl','rsa','-in',str(f.KEY),'-noout','-modulus']).decode().strip().split('=',1)[1]
            f.PUBLIC_KEY={'kty':'RSA','alg':'RS256','use':'sig','kid':'owned-peer','n':f.b64(bytes.fromhex(modulus)),'e':'AQAB'}
            fixture=http.server.ThreadingHTTPServer(('127.0.0.1',0),f.Fixture);f.FIXTURE_URL='http://127.0.0.1:'+str(fixture.server_port);threading.Thread(target=fixture.serve_forever,daemon=True).start()
            for service in ['accounts','ledger','payments']:
                f.PEER_PORTS[service]=f.free_port();name='owned-pay-'+service+'-'+uuid.uuid4().hex[:8]
                container=f.run(f.DOCKER+['run','--rm','-d','--name',name,'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD='+f.BOOTSTRAP_PASSWORD,'postgres:17-alpine']).decode().strip()
                if not re.fullmatch('[a-f0-9]{64}',container):raise RuntimeError('Unexpected owned container ID')
                f.CONTAINERS[service]=container;f.DB_PORTS[service]=int(f.run(f.DOCKER+['port',container,'5432/tcp']).decode().strip().rsplit(':',1)[1])
                for attempt in range(100):
                    try:f.psql(container,'SELECT 1;');break
                    except subprocess.CalledProcessError:time.sleep(.1)
                f.psql(container,'CREATE DATABASE fnd_'+service+';')
                f.psql(container,"CREATE ROLE funds_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"+f.RUNTIME_PASSWORD+"'; CREATE ROLE firefly_funds_policy_reader NOLOGIN NOSUPERUSER NOBYPASSRLS; CREATE ROLE firefly_funds_policy_writer NOLOGIN NOSUPERUSER NOBYPASSRLS; REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO funds_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO funds_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO funds_runtime;",'fnd_'+service)
            f.boot('accounts');f.boot('ledger');boot_payments()
            roles=f.psql(f.CONTAINERS['payments'],"SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='funds_runtime'; SELECT bool_and(relrowsecurity AND relforcerowsecurity AND pg_get_userbyid(relowner)<>'funds_runtime') FROM pg_class WHERE relname IN('managed_payment_operation','managed_payment_action','managed_payment_effect','managed_payment_projection','managed_payment_history','managed_payment_outbox');",'fnd_payments').splitlines()
            f.check(roles==['t','t'],'actual payment runtime is nonprivileged/nonowner and all six managed tables FORCE RLS',roles)
            ids=[]
            for kind in ['BANK_CONTROL','CUSTOMER','CUSTOMER']:
                contract=str(uuid.uuid4());_,account=f.request('accounts','POST','/api/v1/accounts',{'contractId':contract,'accountNumber':'SYNTHETIC-'+str(uuid.uuid4()),'accountType':'CHECKING','currency':'USD','openDate':time.strftime('%Y-%m-%d'),'accountStatus':'OPEN','branchId':str(uuid.uuid4())},expected=201)
                account=account['accountId'];enrollment=str(uuid.uuid4());f.APPROVALS[account]={'tenantId':'owned','accountId':account,'contractId':contract,'currency':'USD','kind':kind};ids.append(account)
                f.request('ledger','POST',f.apath(account)+'/enrollment',{'enrollmentId':enrollment,'contractId':contract,'currency':'USD','kind':kind},[account],['funds:enroll','funds:tenant-bind','funds:bank-enroll'],201)
                f.request('accounts','POST',f.apath(account)+'/enrollment',{'enrollmentId':enrollment,'kind':kind},[account],['funds:enroll','funds:tenant-bind','funds:bank-enroll'],201)
                f.request('accounts','POST',f.apath(account)+'/enrollment/link',{'enrollmentId':enrollment},[account],['funds:link'],200)
                f.request('ledger','POST',f.apath(account)+'/controls',{'actionId':str(uuid.uuid4()),'state':'ACTIVE'},[account],['funds:control'],200)
            bank,source,destination=ids;pair=[source,destination]
            f.request('ledger','POST','/api/v1/tenants/owned/funds/funding',{'operationId':str(uuid.uuid4()),'bankControlAccountId':bank,'customerAccountId':source,'amount':100,'currency':'USD'},[bank,source],['funds:fund'],201)
            def create(amount=10,ttl=300):
                body={'actionId':str(uuid.uuid4()),'sourceAccountId':source,'destinationAccountId':destination,'amount':amount,'currency':'USD','ttlSeconds':ttl,'customerNotAfterEpochMillis':None}
                _,v=payment('POST',body=body,accounts=pair,expected=201);return body,v
            def claim(v):
                _,c=payment('POST','/'+v['paymentId']+'/claims',{'actionId':str(uuid.uuid4()),'expectedVersion':v['version']},pair,200);return c
            def reserve(v):
                intent={'operationId':v['paymentId'],'sourceAccountId':source,'destinationAccountId':destination,'amount':float(v['amount']),'currency':'USD','ttlSeconds':v['ttlSeconds'],'notAfterEpochMillis':v['notAfterEpochMillis']}
                _,r=f.request('ledger','POST','/api/v1/tenants/owned/funds/reservations',intent,pair,['funds:reserve'],201);return intent,r
            def ack(v,c,expected=200):
                return payment('POST','/'+v['paymentId']+'/acknowledgments',{'effectId':c['effectId'],'fence':c['fence'],'expectedVersion':c['version']},pair,expected)[1]
            def recover(v):
                _,r=payment('POST','/'+v['paymentId']+'/recovery',{'actionId':str(uuid.uuid4()),'expectedVersion':v['version']},pair,202);return r
            def act(r,c,action):
                return f.request('ledger','POST','/api/v1/tenants/owned/funds/reservations/'+r['reservationId']+'/actions',{'actionId':c['participantActionId'],'action':action},pair,['funds:'+{'COMMIT':'commit','RELEASE':'release','EXPIRE':'expire'}[action]],200)[1]
            body,v=create(10);c=claim(v);intent,r=reserve(v);f.stop('payments');boot_payments()
            retained=payment('GET','/'+v['paymentId'],accounts=pair,expected=200)[1];f.check(retained['state']=='RESERVING' and retained['pendingEffectId']==c['effectId'],'core restart retains pending reserve after actual ledger effect')
            v=ack(v,c);f.check(v['state']=='RESERVED' and v['reservationId']==r['reservationId'],'genuine ledger SDK full-intent FOUND establishes actual held reservation')
            _,v=payment('POST','/'+v['paymentId']+'/decisions',{'actionId':str(uuid.uuid4()),'kind':'CONFIRM','expectedVersion':v['version']},pair,202);commit=claim(v);act(r,commit,'COMMIT');act(r,commit,'COMMIT');f.stop('payments');boot_payments()
            f.psql(f.CONTAINERS['payments'],"CREATE FUNCTION public.owned_fail_completion_outbox() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF NEW.payload->>'type'='LEDGER_COMMITTED' THEN RAISE EXCEPTION 'OWNED_COMPLETION_OUTBOX_FAILURE'; END IF; RETURN NEW; END$$; CREATE TRIGGER owned_fail_completion_outbox BEFORE INSERT ON managed_payment_outbox FOR EACH ROW EXECUTE FUNCTION public.owned_fail_completion_outbox();",'fnd_payments')
            ack(v,commit,500)
            retained=payment('GET','/'+v['paymentId'],accounts=pair,expected=200)[1]
            f.check(retained['state']=='COMMITTING' and retained['pendingEffectId']==commit['effectId'],'financial acknowledgment/outbox failure rolls back only core progress while real ledger posting remains discoverable')
            f.psql(f.CONTAINERS['payments'],"DROP TRIGGER owned_fail_completion_outbox ON managed_payment_outbox; DROP FUNCTION public.owned_fail_completion_outbox();",'fnd_payments')
            v=ack(v,commit)
            f.check(v['state']=='COMPLETED' and v['postingId']==r['postingId'],'actual commit before core restart/ack is recovered without automatic cancellation')
            f.check(f.funds(source)['booked']==90 and f.funds(source)['held']==0 and f.funds(destination)['booked']==10,'actual source/destination balances move once')
            balanced=f.psql(f.CONTAINERS['ledger'],"SELECT count(*)=2 AND sum(CASE WHEN leg_type='DEBIT' THEN amount ELSE -amount END)=0 AND count(DISTINCT account_id)=2 FROM transaction_leg WHERE transaction_id='"+v['postingId']+"';",'fnd_ledger').strip();f.check(balanced=='t','owned private ledger audit confirms exact balanced two-leg posting')
            payment('POST',body=body,accounts=pair,expected=200);changed=dict(body,amount=11);payment('POST',body=changed,accounts=pair,expected=409);payment('POST',body=body,accounts=[source],expected=403)
            f.request('ledger','POST','/api/v1/tenants/owned/funds/reservations/'+r['reservationId']+'/actions',{'actionId':str(uuid.uuid4()),'action':'REVERSE'},pair,['funds:reverse'],200)
            v=recover(v);observation=claim(v);v=ack(v,observation);f.check(v['state']=='REVERSED' and v['postingId']==r['postingId'] and v['reversalId']!=v['postingId'],'genuine later reversal remains distinct and linked')
            history=payment('GET','/'+v['paymentId']+'/history',accounts=pair,expected=200)[1];f.check(any(e['type']=='LEDGER_COMMITTED' for e in history) and any(e['type']=='LEDGER_REVERSED' for e in history),'original completion and distinct reversal history survive')
            _,v=create(5);c=claim(v);_,r=reserve(v);v=ack(v,c);_,v=payment('POST','/'+v['paymentId']+'/decisions',{'actionId':str(uuid.uuid4()),'kind':'CANCEL','expectedVersion':v['version']},pair,202);release=claim(v);act(r,release,'RELEASE');v=ack(v,release);f.check(v['state']=='CANCELLED' and f.funds(source)['held']==0,'actual release proves cancellation without changing booked balances')
            absent_body,v=create(1,1);c=claim(v);time.sleep(max(0,(v['notAfterEpochMillis']-time.time()*1000)/1000)+.02);v=ack(v,c)
            f.check(v['state']=='EXPIRED' and v['absenceReceiptId'] and v['reservationId'] is None and v['postingId'] is None,'actual native closed-window receipt expires without fake money')
            f.request('ledger','POST','/api/v1/tenants/owned/funds/reservations',{'operationId':v['paymentId'],'sourceAccountId':source,'destinationAccountId':destination,'amount':1,'currency':'USD','ttlSeconds':1,'notAfterEpochMillis':v['notAfterEpochMillis']},pair,['funds:reserve'],409)
            f.stop('ledger');f.boot('ledger');f.stop('payments');boot_payments();retained=payment('GET','/'+v['paymentId'],accounts=pair,expected=200)[1];f.check(retained['absenceReceiptId']==v['absenceReceiptId'],'actual no-effect receipt reference survives both core restarts')
            f.check(f.funds(source)['booked']==100 and f.funds(source)['held']==0 and f.funds(destination)['booked']==0 and f.funds(bank)['booked']==-100,'final actual balances conserve explicit bank-control funding and contain no orphan hold')
            # The same immutable expiry action first fails early, then succeeds on the ledger clock.
            _,v=create(1,2);reserve_claim=claim(v);_,expiry_reservation=reserve(v);v=ack(v,reserve_claim)
            v=recover(v);early=claim(v)
            expiry_path='/api/v1/tenants/owned/funds/reservations/'+expiry_reservation['reservationId']+'/actions'
            expiry_action={'actionId':early['participantActionId'],'action':'EXPIRE'}
            f.request('ledger','POST',expiry_path,expiry_action,pair,['funds:expire'],409)
            v=ack(v,early);f.check(v['state']=='RESERVED' and v['pendingEffectId'] is None,'early ledger expiry rejection leaves held instruction discoverable without local expiry')
            expiry_at=datetime.datetime.fromisoformat(expiry_reservation['expiresAt'].replace('Z','+00:00')).timestamp()
            time.sleep(max(0,expiry_at-time.time())+.03)
            due=payment('GET','/recovery?limit=100',accounts=pair,expected=200)[1]
            f.check(any(x['paymentId']==v['paymentId'] and x['pendingEffectId'] is None for x in due['items']),'due held instruction without pending effect is in bounded recovery queue')
            v=recover(v);later=claim(v)
            f.check(later['participantActionId']==early['participantActionId'],'explicit expiry reuses persisted observation participant identity')
            f.request('ledger','POST',expiry_path,expiry_action,pair,['funds:read'],403)
            f.request('ledger','POST',expiry_path,expiry_action,pair,['funds:expire'],200)
            f.request('ledger','POST',expiry_path,expiry_action,pair,['funds:expire'],200)
            v=ack(v,later)
            f.check(v['state']=='EXPIRED' and v['reservationId']==expiry_reservation['reservationId'] and v['absenceReceiptId'] is None and f.funds(source)['held']==0,'actual ledger expiry and retained exact action do not become closed-window absence')
            f.PEER_PORTS['payments-secondary']=f.free_port();boot_payments('payments-secondary')
            _,v=create(1);race_reserve_claim=claim(v);_,race_reservation=reserve(v);v=ack(v,race_reserve_claim)
            barrier=threading.Barrier(2)
            def opposite(kind,replica):
                barrier.wait(timeout=10)
                return kind,payment('POST','/'+v['paymentId']+'/decisions',
                    {'actionId':str(uuid.uuid4()),'kind':kind,'expectedVersion':v['version']},pair,replica=replica)
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                futures=[pool.submit(opposite,'CONFIRM','payments'),pool.submit(opposite,'CANCEL','payments-secondary')]
                decisions=[future.result(timeout=25) for future in futures]
            f.check(sorted(response[0] for kind,response in decisions)==[202,409],'two real payment replicas sharing one database have one opposite-decision winner',[(k,r[0]) for k,r in decisions])
            winner,(_,v)=next((kind,response) for kind,response in decisions if response[0]==202)
            race_effect=claim(v);act(race_reservation,race_effect,'COMMIT' if winner=='CONFIRM' else 'RELEASE');v=ack(v,race_effect)
            f.check(v['state']==('COMPLETED' if winner=='CONFIRM' else 'CANCELLED'),'only winning decision determines actual financial effect')
            if winner=='CONFIRM':
                f.request('ledger','POST','/api/v1/tenants/owned/funds/reservations/'+race_reservation['reservationId']+'/actions',{'actionId':str(uuid.uuid4()),'action':'REVERSE'},pair,['funds:reverse'],200)
                v=recover(v);race_observe=claim(v);v=ack(v,race_observe)
                f.check(v['state']=='REVERSED','race cleanup uses actual authorized reversal preserving completed instruction')
            f.stop('payments-secondary')
            probe_env=dict(os.environ,OWNED_PAYMENTS_URL='http://127.0.0.1:'+str(f.PEER_PORTS['payments']),
                OWNED_PAYMENTS_TOKEN=f.token(['payments-managed','ledger-funds'],pair,SCOPES),
                OWNED_REVOKED_TOKEN=f.token(['payments-managed','ledger-funds'],[source],SCOPES),
                OWNED_SOURCE_ACCOUNT=source,OWNED_DESTINATION_ACCOUNT=destination)
            probe_log=f.ROOT/'payment-sdk-consumer.log'
            with probe_log.open('w') as out:
                subprocess.run([args.java,'-cp',str(deps)+os.pathsep+classpath,'ManagedPaymentsSdkProbe'],env=probe_env,stdout=out,stderr=subprocess.STDOUT,check=True)
            probe_checks=[line.split('=',1)[1] for line in probe_log.read_text().splitlines() if line.startswith('OWNED_PAYMENT_SDK_CHECK=')]
            f.check(len(probe_checks)==11,'genuine payment SDK completed all eleven actual-core consumer assertions',len(probe_checks))
            for description in probe_checks:f.check(True,'payment SDK: '+description)
            report['sdk_assertions']=len(probe_checks)
            report['status']='PASS'
    except BaseException as error:
        report['error']=type(error).__name__+(': '+str(error)[:1000] if not isinstance(error,subprocess.CalledProcessError) else ': owned subprocess failed')
        raise
    finally:
        for service in list(f.PROCESSES):f.stop(service)
        if fixture:fixture.shutdown();fixture.server_close()
        for container in f.CONTAINERS.values():subprocess.run(f.DOCKER+['stop','--time','5',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        report.update(assertions=len(f.ASSERTIONS),checks=f.ASSERTIONS,logs=f.LOG_FILES)
        (f.ROOT/'reports/managed-payment-core-result.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps({'status':report['status'],'assertions':len(f.ASSERTIONS)}),flush=True)


if __name__=='__main__':main()
