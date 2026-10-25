#!/usr/bin/env python3
"""Run a frozen genuine SDK against the actual executable and a new loopback PostgreSQL store.

This verifies legacy child parentage only. It does not establish tenant/actor authorization,
FX quote authority, fee assessment, charging, or a managed-payment financial outcome.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payments-jar', type=pathlib.Path, required=True)
    parser.add_argument('--payments-sdk', type=pathlib.Path, required=True)
    parser.add_argument('--output-dir', type=pathlib.Path, required=True)
    parser.add_argument('--docker-host', default=os.environ.get('DOCKER_HOST'))
    parser.add_argument('--java', default=str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java'))
    args = parser.parse_args()
    probe = pathlib.Path(__file__).with_name('PaymentOrderChildParentageSdkProbe.java')
    for path in (args.payments_jar, args.payments_sdk, probe):
        if not path.is_file():
            parser.error('Missing input: ' + str(path))
    args.output_dir.mkdir(parents=True, exist_ok=False)
    docker = [shutil.which('docker')] + (['--host', args.docker_host] if args.docker_host else [])
    process = container = log = None
    result = {'status': 'FAILED', 'scope': 'legacy payment-order child parentage', 'inputs': {
        str(p.resolve()): digest(p) for p in (args.payments_jar, args.payments_sdk, probe, pathlib.Path(__file__))}}
    checks = []

    def run(command, text=None):
        return subprocess.check_output(command, input=None if text is None else text.encode(), stderr=subprocess.PIPE).decode().strip()

    def sql(statement):
        return run(docker + ['exec', '-i', container, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'synthetic_children'], statement)

    def check(ok, message):
        if not ok:
            raise AssertionError(message)
        checks.append(message)

    try:
        with tempfile.TemporaryDirectory(prefix='owned-payment-child-') as temporary:
            temporary = pathlib.Path(temporary)
            boot = temporary / 'payments.jar'; sdk = temporary / 'payments-sdk.jar'
            shutil.copy2(args.payments_jar, boot); shutil.copy2(args.payments_sdk, sdk)
            check(digest(boot) == digest(args.payments_jar) and digest(sdk) == digest(args.payments_sdk), 'Copied input hashes match')
            dependencies = temporary / 'dependencies'; dependencies.mkdir()
            with zipfile.ZipFile(boot) as archive:
                for name in archive.namelist():
                    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                        (dependencies / pathlib.Path(name).name).write_bytes(archive.read(name))
            classpath = os.pathsep.join([str(sdk), str(dependencies / '*')])
            with (args.output_dir / 'sdk-compile.log').open('w') as output:
                subprocess.run([str(pathlib.Path(args.java).with_name('javac')), '-cp', classpath, '-d', str(dependencies), str(probe)], stdout=output, stderr=subprocess.STDOUT, check=True)
            password = secrets.token_urlsafe(24); runtime = secrets.token_urlsafe(24)
            container = run(docker + ['run', '--rm', '-d', '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD=' + password, 'postgres:17-alpine'])
            if not re.fullmatch('[a-f0-9]{64}', container):
                raise RuntimeError('Unexpected owned container identifier')
            db_port = run(docker + ['port', container, '5432/tcp']).rsplit(':', 1)[1]
            for attempt in range(100):
                try:
                    run(docker + ['exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres']); break
                except subprocess.CalledProcessError:
                    time.sleep(.1)
            else:
                raise RuntimeError('Owned PostgreSQL TCP startup timeout')
            run(docker + ['exec', container, 'createdb', '-U', 'postgres', 'synthetic_children'])
            sql("CREATE ROLE child_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + runtime + "'; GRANT USAGE ON SCHEMA public TO child_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO child_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO child_runtime;")
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
            env = dict(os.environ, DB_HOST='127.0.0.1', DB_PORT=db_port, DB_NAME='synthetic_children', DB_USERNAME='child_runtime', DB_PASSWORD=runtime,
                       DB_SSL_MODE='disable', SPRING_FLYWAY_USER='postgres', SPRING_FLYWAY_PASSWORD=password, SERVER_ADDRESS='127.0.0.1', SERVER_PORT=str(port),
                       FIREFLY_EDA_ENABLED='false', LOGGING_LEVEL_ROOT='WARN')
            log = (args.output_dir / 'payments-boot.log').open('w')
            process = subprocess.Popen([args.java, '-jar', str(boot)], env=env, stdout=log, stderr=subprocess.STDOUT)
            base = 'http://127.0.0.1:' + str(port)
            for attempt in range(450):
                if process.poll() is not None:
                    raise RuntimeError('Executable exited; inspect payments-boot.log')
                try:
                    with urllib.request.urlopen(base + '/v3/api-docs', timeout=1) as response:
                        specification = json.load(response)
                    break
                except (OSError, urllib.error.HTTPError):
                    time.sleep(.2)
            else:
                raise RuntimeError('Executable startup timeout')
            (args.output_dir / 'runtime-openapi.json').write_text(json.dumps(specification, indent=2) + '\n')
            a,b,fx_a,fx_a2,fx_b,fee_a,fee_a2,fee_b = [str(uuid.uuid4()) for _ in range(8)]
            for parent in (a,b):
                sql("INSERT INTO payment_order(payment_order_id,payer_account_id,beneficiary_name,payment_type,status,amount,currency_code) VALUES('"+parent+"','"+str(uuid.uuid4())+"','Synthetic parent','SEPA_SCT','Initiated',100,'USD');")
            for child,parent in ((fx_a,a),(fx_a2,a),(fx_b,b)):
                sql("INSERT INTO payment_exchange_rate(payment_exchange_rate_id,payment_order_id,source_currency,target_currency,rate,rate_date,rate_provider,rate_type,original_amount,converted_amount) VALUES('"+child+"','"+parent+"','USD','EUR',0.9,'2026-10-01','Synthetic original','SPOT',100,90);")
            for child,parent in ((fee_a,a),(fee_a2,a),(fee_b,b)):
                sql("INSERT INTO payment_fee(payment_fee_id,payment_order_id,fee_type,fee_amount,fee_currency_code) VALUES('"+child+"','"+parent+"','LEGACY_UNCLASSIFIED',1,'USD');")
            host_before = time.time_ns() // 1000000
            database_now = int(sql("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint;"))
            host_after = time.time_ns() // 1000000
            result['clock_diagnostic'] = {'host_before_epoch_ms':host_before, 'database_epoch_ms':database_now, 'host_after_epoch_ms':host_after}
            check(sql("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='child_runtime';")=='t', 'Actual Boot runtime uses a nonprivileged database role')
            probe_env = dict(os.environ, OWNED_PAYMENTS_URL=base, OWNED_PARENT_A=a, OWNED_PARENT_B=b, OWNED_FX_B=fx_b, OWNED_FEE_A=fee_a)
            with (args.output_dir / 'sdk-consumer.log').open('w') as output:
                subprocess.run([args.java, '-cp', str(dependencies)+os.pathsep+classpath, 'PaymentOrderChildParentageSdkProbe'], env=probe_env, stdout=output, stderr=subprocess.STDOUT, check=True)
            sdk_checks = [line.split('=',1)[1] for line in (args.output_dir / 'sdk-consumer.log').read_text().splitlines() if line.startswith('CHILD_SDK_CHECK=')]
            check(len(sdk_checks)==19, 'All nineteen genuine SDK assertions completed')
            checks.extend('SDK: '+description for description in sdk_checks)
            check(sql("SELECT payment_order_id='"+b+"'::uuid AND rate_provider='Synthetic original' FROM payment_exchange_rate WHERE payment_exchange_rate_id='"+fx_b+"';")=='t', 'Native foreign FX row remains unchanged')
            check(sql("SELECT count(*)=2 AND count(fee_type_enum)=0 FROM payment_fee WHERE payment_order_id='"+b+"' OR payment_fee_id='"+fee_a+"';")=='t', 'Native legacy fee rows remain unclassified')
            check(sql("SELECT fee_type_enum='TRANSACTION_FEE' AND fee_type='Explicit SDK legacy label' AND fee_amount=3.25 AND payment_order_id='"+a+"'::uuid FROM payment_fee WHERE fee_type='Explicit SDK legacy label';")=='t', 'Native typed fee and independent legacy label persist under route parent')
            result['status'] = 'PASS'
    except BaseException as error:
        result['error'] = type(error).__name__ + ': ' + (str(error)[:500] if not isinstance(error, subprocess.CalledProcessError) else 'owned subprocess failed; inspect logs')
        raise
    finally:
        if process is not None:
            process.terminate()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        if log is not None: log.close()
        container_absent = container is None
        if container is not None:
            subprocess.run(docker + ['stop', '--time', '5', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            inspection = subprocess.run(docker + ['ps', '-a', '--filter', 'id=' + container, '--format', '{{.ID}}'], capture_output=True, text=True)
            container_absent = inspection.returncode == 0 and not inspection.stdout.strip()
        result.update(assertions=len(checks), checks=checks, cleanup={'process_stopped':process is None or process.poll() is not None, 'owned_container_absent':container_absent})
        if not result['cleanup']['process_stopped'] or not container_absent:
            result['status'] = 'FAILED'
            result['error'] = 'Owned process/container cleanup could not be verified'
        (args.output_dir / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({'status':result['status'], 'assertions':len(checks)}), flush=True)


if __name__ == '__main__':
    main()
