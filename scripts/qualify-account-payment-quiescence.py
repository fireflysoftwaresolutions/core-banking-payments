#!/usr/bin/env python3
"""Qualify original payment/ledger/account owners with a genuine generated payment client.

Only new disposable PostgreSQL resources and synthetic local issuer/PDP fixtures are used.
The policy fixture supplies explicit current authority; no financial producer/ledger adapter
or receipt is replaced. Business accounts, enrollment and instructions are created via APIs.
"""
import argparse,concurrent.futures,hashlib,http.server,importlib.util,json,os,pathlib,re,secrets,shutil,subprocess,tempfile,threading,time,urllib.error,urllib.request,uuid,zipfile

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['accounts-jar','ledger-jar','payments-jar','payments-sdk','funds-fixture-script','policy-fixture-source','output-dir']:p.add_argument('--'+name,type=pathlib.Path,required=True)
    p.add_argument('--probe-source',type=pathlib.Path,default=pathlib.Path(__file__).with_name('PaymentAccountQuiescenceSdkProbe.java'))
    p.add_argument('--java',default=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java')
    p.add_argument('--docker-host',default=os.environ.get('DOCKER_HOST'))
    a=p.parse_args();out=a.output_dir.resolve();out.mkdir(exist_ok=False);(out/'inputs').mkdir();(out/'artifacts').mkdir()
    report={'status':'FAILED','scope':'native payment quiescence and actual ledger tuple transport; no business closure acceptance','checks':[],'artifacts':{}}
    def check(condition,name):
        if not condition:raise AssertionError(name)
        report['checks'].append(name);print('PASS '+name,flush=True)
    for name,path in [('funds_fixture.py',a.funds_fixture_script),('OwnedClosurePolicyConfiguration.java',a.policy_fixture_source),('PaymentAccountQuiescenceSdkProbe.java',a.probe_source)]:
        shutil.copy2(path,out/'inputs'/name)
    spec=importlib.util.spec_from_file_location('owned_funds_fixture',out/'inputs/funds_fixture.py');f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
    f.ROOT=out;f.JAVA=a.java;f.DOCKER=['docker']+(['--host',a.docker_host] if a.docker_host else []);f.CONTAINERS={};f.DB_PORTS={};f.JARS={};f.PROCESSES={};f.LOG_FILES=[];f.APPROVALS={};f.PEER_PORTS={}
    f.BOOTSTRAP_PASSWORD=secrets.token_urlsafe(24);f.RUNTIME_PASSWORD=secrets.token_urlsafe(24);f.CLIENT_SECRET=secrets.token_urlsafe(24)
    fixture=None;libraries=out/'libraries';libraries.mkdir();classes=out/'probe-classes';classes.mkdir();policy_classes=out/'policy-classes';policy_classes.mkdir()
    policy_file=out/'policy.json';policy={'enabled':True,'accounts':{}}
    def save_policy():
        pending=policy_file.with_suffix('.pending');pending.write_text(json.dumps(policy));pending.replace(policy_file)
    for name,path in [('accounts',a.accounts_jar),('ledger',a.ledger_jar),('payments',a.payments_jar),('payments_sdk',a.payments_sdk)]:
        target=out/'artifacts'/(name+'.jar');shutil.copy2(path,target);report['artifacts'][name]={'source':str(path.resolve()),'sha256':hashlib.sha256(target.read_bytes()).hexdigest()}
        if name!='payments_sdk':f.JARS[name]=target
    with zipfile.ZipFile(f.JARS['payments']) as z:
        for name in z.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(libraries/pathlib.Path(name).name).write_bytes(z.read(name))
    cp=str(out/'artifacts/payments_sdk.jar')+os.pathsep+str(libraries/'*')
    for source,target in [(out/'inputs/OwnedClosurePolicyConfiguration.java',policy_classes),(out/'inputs/PaymentAccountQuiescenceSdkProbe.java',classes)]:
        r=subprocess.run([str(pathlib.Path(a.java).with_name('javac')),'-proc:none','-cp',cp,'-d',str(target),str(source)],capture_output=True,text=True)
        (out/(source.stem+'-compile.log')).write_text(r.stdout+r.stderr);check(r.returncode==0,'compile '+source.stem)
    def boot_payments(with_policy=True):
        log=(out/('payments-boot-'+str(time.time_ns())+'.log')).open('w');f.LOG_FILES.append(log.name)
        env=dict(os.environ,DB_HOST='127.0.0.1',DB_PORT=str(f.DB_PORTS['payments']),DB_NAME='fnd_payments',DB_USERNAME='funds_runtime',DB_PASSWORD=f.RUNTIME_PASSWORD,DB_SSL_MODE='disable',SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(f.PEER_PORTS['payments']),SPRING_FLYWAY_USER='postgres',SPRING_FLYWAY_PASSWORD=f.BOOTSTRAP_PASSWORD,FIREFLY_EDA_ENABLED='false',OTEL_SDK_DISABLED='true',MANAGED_PAYMENTS_SECURITY_ISSUER=f.ISSUER,MANAGED_PAYMENTS_SECURITY_AUDIENCE='payments-managed',MANAGED_PAYMENTS_SECURITY_JWK_SET_URI=f.FIXTURE_URL+'/jwks',MANAGED_PAYMENTS_ALLOW_LOOPBACK_HTTP='true',MANAGED_PAYMENTS_DELEGATION_MODE='validated-multi-audience',MANAGED_PAYMENTS_PEERS_LEDGER_BASE_URL='http://127.0.0.1:'+str(f.PEER_PORTS['ledger']),MANAGED_PAYMENTS_PEERS_LEDGER_AUDIENCE='ledger-funds',FIREFLY_SECURITY_RESOURCE_SERVER_STRICT_POLICY='true',FIREFLY_SECURITY_RESOURCE_SERVER_ISSUER=f.ISSUER,FIREFLY_SECURITY_RESOURCE_SERVER_AUDIENCES='payments-managed',FIREFLY_SECURITY_RESOURCE_SERVER_JWK_SET_URI=f.FIXTURE_URL+'/jwks',FIREFLY_SECURITY_RESOURCE_SERVER_PERMIT_MATCHERS='/v3/api-docs/**',OWNED_CLOSURE_POLICY_FILE=str(policy_file),LOGGING_LEVEL_ROOT='WARN')
        if with_policy:env['SPRING_MAIN_SOURCES']='owned.fixture.OwnedClosurePolicyConfiguration'
        else:env.pop('SPRING_MAIN_SOURCES',None)
        process=subprocess.Popen([a.java,'-Xmx384m','-Dloader.path='+str(policy_classes),'-Dloader.main=io.fireflysoftware.core.banking.payments.web.PaymentsApplication','-cp',str(f.JARS['payments']),'org.springframework.boot.loader.launch.PropertiesLauncher'],env=env,stdout=log,stderr=subprocess.STDOUT);f.PROCESSES['payments']=(process,log)
        deadline=time.monotonic()+60
        while time.monotonic()<deadline:
            if process.poll() is not None:raise RuntimeError('payments startup failed; inspect '+log.name)
            try:urllib.request.urlopen('http://127.0.0.1:'+str(f.PEER_PORTS['payments'])+'/v3/api-docs',timeout=1).close();break
            except (OSError,urllib.error.HTTPError):time.sleep(.2)
        else:raise RuntimeError('payments startup timeout')
        if with_policy:check(pathlib.Path(str(policy_file)+'.loaded').exists(),'explicit strict owned PolicyRule loaded')
    def probe(entry,mode='quiesce',expected_http=None,expected_observation=None,resources=None,destination=None,action=None,actor='synthetic-closure-worker',tenant='owned'):
        label=entry['label']+'-'+mode+'-'+str(time.time_ns());result=out/(label+'.json')
        scopes=['payments:closure:quiesce','payments:closure:read','payments:closure:observe','payments:closure:release','funds:read','payments:create','payments:coordinate','funds:reserve','funds:reconcile']
        claims={'iss':f.ISSUER,'aud':['payments-managed','ledger-funds'],'sub':actor,'tenant_id':tenant,'scope':' '.join(scopes),'funds_accounts':resources or [entry['account']],'iat':int(time.time()),'exp':int(time.time())+120}
        unsigned=f.b64(json.dumps({'alg':'RS256','kid':'owned-peer'}).encode())+'.'+f.b64(json.dumps(claims).encode())
        signed=unsigned+'.'+f.b64(f.run(['openssl','dgst','-sha256','-sign',str(f.KEY)],input=unsigned.encode()))
        env=dict(os.environ,OWNED_PAYMENTS_URL='http://127.0.0.1:'+str(f.PEER_PORTS['payments']),OWNED_CLOSURE_TOKEN=signed,OWNED_ACCOUNT=entry['account'],OWNED_CLOSURE=entry['closure'],OWNED_ACTION=action or entry['action'],OWNED_LEDGER_RECORD=str(entry['record']),OWNED_PROBE_MODE=mode,OWNED_PROBE_RESULT=str(result))
        if expected_http:env['OWNED_EXPECTED_HTTP']=str(expected_http)
        if expected_observation:env['OWNED_EXPECTED_OBSERVATION']=expected_observation
        if destination:env['OWNED_DESTINATION']=destination
        if mode=='replay':env['OWNED_ORIGINAL_RECEIPT']=str(entry['receipt'])
        r=subprocess.run([a.java,'-Xmx256m','-cp',str(classes)+os.pathsep+cp,'PaymentAccountQuiescenceSdkProbe'],env=env,capture_output=True,text=True,timeout=35)
        (out/(label+'.log')).write_text(r.stdout+r.stderr)
        if r.returncode:raise AssertionError('genuine generated-client '+mode+' failed; inspect '+label+'.log')
        report.setdefault('sdk_checks',[]).extend(re.findall(r'CLOSURE_SDK_CHECK=(.*)',r.stdout))
        check(result.exists(),'genuine SDK '+mode+' result retained')
        if mode=='quiesce' and not expected_http:entry['receipt']=result
        return json.loads(result.read_text())
    try:
        f.KEY=out/'owned-signing.pem';f.run(['openssl','genrsa','-out',str(f.KEY),'2048']);os.chmod(f.KEY,0o600)
        modulus=f.run(['openssl','rsa','-in',str(f.KEY),'-noout','-modulus']).decode().strip().split('=',1)[1]
        f.PUBLIC_KEY={'kty':'RSA','alg':'RS256','use':'sig','kid':'owned-peer','n':f.b64(bytes.fromhex(modulus)),'e':'AQAB'}
        fixture=http.server.ThreadingHTTPServer(('127.0.0.1',0),f.Fixture);f.FIXTURE_URL='http://127.0.0.1:'+str(fixture.server_port);threading.Thread(target=fixture.serve_forever,daemon=True).start()
        for name in ['accounts','ledger','payments']:
            f.PEER_PORTS[name]=f.free_port();container=f.run(f.DOCKER+['run','--rm','-d','--name','owned-closure-'+name+'-'+uuid.uuid4().hex[:8],'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD='+f.BOOTSTRAP_PASSWORD,'postgres:17-alpine']).decode().strip()
            if not re.fullmatch('[a-f0-9]{64}',container):raise RuntimeError('invalid owned container ID')
            f.CONTAINERS[name]=container;f.DB_PORTS[name]=int(f.run(f.DOCKER+['port',container,'5432/tcp']).decode().strip().rsplit(':',1)[1])
            for attempt in range(100):
                try:f.psql(container,'SELECT 1;');break
                except subprocess.CalledProcessError:time.sleep(.1)
            f.psql(container,'CREATE DATABASE fnd_'+name+';')
            f.psql(container,"CREATE ROLE funds_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"+f.RUNTIME_PASSWORD+"'; CREATE ROLE firefly_funds_policy_reader NOLOGIN NOSUPERUSER NOBYPASSRLS; CREATE ROLE firefly_funds_policy_writer NOLOGIN NOSUPERUSER NOBYPASSRLS; REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO funds_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO funds_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO funds_runtime;",'fnd_'+name)
        f.boot('accounts');f.boot('ledger');entries=[]
        for index in range(7):
            kind='BANK_CONTROL' if index==6 else 'CUSTOMER';contract=str(uuid.uuid4());enrollment=str(uuid.uuid4())
            _,created=f.request('accounts','POST','/api/v1/accounts',{'contractId':contract,'accountNumber':'SYNTHETIC-'+str(uuid.uuid4()),'accountType':'CHECKING','currency':'USD','openDate':time.strftime('%Y-%m-%d'),'accountStatus':'OPEN','branchId':str(uuid.uuid4())},expected=201)
            account=created['accountId'];f.APPROVALS[account]={'tenantId':'owned','accountId':account,'contractId':contract,'currency':'USD','kind':kind}
            f.request('ledger','POST',f.apath(account)+'/enrollment',{'enrollmentId':enrollment,'contractId':contract,'currency':'USD','kind':kind},[account],['funds:enroll','funds:tenant-bind','funds:bank-enroll'],201)
            f.request('accounts','POST',f.apath(account)+'/enrollment',{'enrollmentId':enrollment,'kind':kind},[account],['funds:enroll','funds:tenant-bind','funds:bank-enroll'],201)
            f.request('accounts','POST',f.apath(account)+'/enrollment/link',{'enrollmentId':enrollment},[account],['funds:link'],200)
            f.request('ledger','POST',f.apath(account)+'/controls',{'actionId':str(uuid.uuid4()),'state':'ACTIVE'},[account],['funds:control'],200)
            _,record=f.request('ledger','GET',f.apath(account),accounts=[account],scopes=['funds:read'],expected=200)
            record_path=out/('real-ledger-'+str(index)+'.json');record_path.write_text(json.dumps(record))
            entry={'label':str(index),'account':account,'closure':str(uuid.uuid4()),'action':str(uuid.uuid4()),'record':record_path};entries.append(entry)
            policy['accounts'][account]={**record,'actorSubjects':['synthetic-closure-worker','synthetic-closure-alpha','synthetic-closure-beta'],'closureIds':[entry['closure']],'actions':{entry['action']:{'operation':'quiesce','closureId':entry['closure'],'expectedFenceVersion':0}}}
        save_policy();boot_payments()
        first,blocked,empty,unfenced,absence_destination,absence_source,bank=entries
        unsigned_body={'actionId':first['action'],'closureId':first['closure'],'enrollmentId':json.loads(first['record'].read_text())['enrollmentId'],'expectedFenceVersion':0}
        unsigned=urllib.request.Request('http://127.0.0.1:'+str(f.PEER_PORTS['payments'])+'/api/v1/tenants/owned/internal-payments/accounts/'+first['account']+'/quiescence',data=json.dumps(unsigned_body).encode(),method='POST',headers={'Content-Type':'application/json','X-Idempotency-Key':first['action']})
        try:urllib.request.urlopen(unsigned,timeout=10);raise AssertionError('missing bearer admitted native command')
        except urllib.error.HTTPError as error:check(error.code==403,'ordinary missing-bearer unsafe request403 at native CSRF boundary')
        probe(first,'get',expected_http=404)
        probe(first,'create',resources=[first['account'],blocked['account']],destination=blocked['account'])
        probe(unfenced,'create',resources=[unfenced['account'],blocked['account']],destination=blocked['account'])
        f.request('ledger','POST',f.apath(blocked['account'])+'/controls',{'actionId':str(uuid.uuid4()),'state':'BLOCKED'},[blocked['account']],['funds:control'],200)
        _,record=f.request('ledger','GET',f.apath(blocked['account']),accounts=[blocked['account']],scopes=['funds:read'],expected=200)
        blocked['record'].write_text(json.dumps(record));policy['accounts'][blocked['account']].update(record);save_policy()
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
            calls=[executor.submit(probe,first,actor='synthetic-closure-alpha'),executor.submit(probe,blocked,actor='synthetic-closure-beta'),executor.submit(probe,empty,expected_http=403,actor='synthetic-closure-wrong-tenant',tenant='other')]
            for call in calls:call.result(timeout=40)
        check(pathlib.Path(str(policy_file)+'.concurrency').exists(),'nonblocking authority barrier overlapped disjoint signed actors')
        probe(first,'header-conflict',expected_http=409)
        probe(unfenced,'create',resources=[unfenced['account'],first['account']],destination=first['account'],action=str(uuid.uuid4()),expected_http=409)
        probe(first,'create',resources=[first['account'],unfenced['account']],destination=unfenced['account'],action=str(uuid.uuid4()),expected_http=409)
        record=json.loads(blocked['record'].read_text())
        check(record['state']=='BLOCKED' and record['held']==0,'real destination is blocked with zero held despite retained incoming instructions')
        probe(blocked,'replay');page=probe(blocked,'observe',expected_observation='OBLIGATIONS')
        check(page['observation']['obligationCount']==2,'complete owner observation includes both incoming instructions')
        check(not re.search(r'"(sourceAccountId|destinationAccountId|amount|paymentId)"',json.dumps(page)),'account-only generated observation exposes no counterparty/payment detail')
        probe(bank,expected_http=503);probe(bank,'get',expected_http=404)
        probe(absence_source,'expire',resources=[absence_source['account'],absence_destination['account']],destination=absence_destination['account'],action=str(uuid.uuid4()))
        probe(absence_destination);probe(absence_destination,'observe',expected_observation='CLEAR')
        probe(empty);probe(empty,'observe',expected_observation='CLEAR')
        release_action=str(uuid.uuid4());policy['accounts'][empty['account']]['actions'][release_action]={'operation':'release','closureId':empty['closure'],'expectedFenceVersion':1};save_policy()
        probe(empty,'release',action=release_action);probe(empty,'replay')
        probe(unfenced,'create',resources=[unfenced['account'],empty['account']],destination=empty['account'],action=str(uuid.uuid4()))
        policy['enabled']=False;save_policy();probe(first,'replay',expected_http=403);probe(first,'get',expected_http=403)
        policy['enabled']=True;save_policy();f.stop('payments');pathlib.Path(str(policy_file)+'.loaded').unlink(missing_ok=True);boot_payments(False)
        check(not pathlib.Path(str(policy_file)+'.loaded').exists(),'unconfigured strict policy has no owned permit rule')
        probe(first,'replay',expected_http=403)
        f.stop('payments');f.stop('ledger');boot_payments(True);probe(blocked,'replay');probe(blocked,'get')
        check('ledger' not in f.PROCESSES,'blocked restart replay uses retained owner identity without requiring live ledger')
        report['status']='PASS'
    except BaseException as error:
        report['error']=type(error).__name__+': '+str(error)
        raise
    finally:
        for name in list(f.PROCESSES):f.stop(name)
        if fixture:fixture.shutdown();fixture.server_close()
        cleanup={}
        for name,container in f.CONTAINERS.items():
            subprocess.run(f.DOCKER+['stop',container],capture_output=True,text=True)
            r=subprocess.run(f.DOCKER+['inspect',container],capture_output=True,text=True);cleanup[name]={'id':container,'absent':'no such object' in r.stderr.lower()}
        if f.KEY:f.KEY.unlink(missing_ok=True)
        report['owned_cleanup']=cleanup;report['owned_processes_remaining']=list(f.PROCESSES)
        (out/'owned-cleanup.json').write_text(json.dumps(cleanup,indent=2)+'\n')
        report['input_hashes']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (out/'inputs').iterdir() if p.is_file()}
        report['logs']=f.LOG_FILES;(out/'result.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps({'status':report['status'],'checks':len(report['checks']),'sdk_checks':len(report.get('sdk_checks',[])),'owned_cleanup':cleanup}),flush=True)

if __name__=='__main__':main()
