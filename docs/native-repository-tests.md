# Native managed-payment repository tests

`ManagedPaymentRepositoryNativeIT` checks the actual Spring Data repositories, native functions, constrained runtime role, one-connection transactions and cancellation cleanup. Its `*IT` name requires explicit invocation; the default Maven test selection does not run this class. These tests do not start HTTP owners or prove financial journeys.

Use Java 25, Maven and PostgreSQL 17. Resolve the declared dependencies through your configured Maven settings; do not replace them with stubs or install another lane's local artifacts. The example below creates a new loopback-only PostgreSQL cluster and uses a private Maven cache. It applies the 15 current migrations, creates the same non-owner/NOBYPASSRLS runtime boundary used by the tests, runs the 12 cases, and stops its own cluster on exit. It retains the cluster data, database log and Maven reports for inspection.

Artifact availability is a separate prerequisite: this source declares the Firefly platform parent/BOM and owner dependencies at `26.10.6`; that parent pins the framework parent/BOM at `26.10.22`, and coordinated artifact publication is pending. Use settings that can resolve the exact qualified artifacts, or provide `PAYMENT_TEST_CACHE` pointing to a private copy of the pinned qualification cache with those exact parent/POM/JAR inputs. An arbitrary cache containing the same version labels is not sufficient. Preserve its dependency checksums and resolution provenance; do not install replacements into a shared cache. If the required artifacts are unavailable, stop at dependency setup rather than treating a launcher failure as a test result.

Set `JAVA_HOME`, `MAVEN_USER_SETTINGS` and `PAYMENT_TEST_PG_BIN` for your machine. `PAYMENT_TEST_PG_BIN` must contain PostgreSQL 17's `initdb`, `pg_ctl` and `psql`. Run this Bash script from the repository root. Do not point it at an existing cluster or service.

```bash
#!/usr/bin/env bash
set -euo pipefail
: "${JAVA_HOME:?Set JAVA_HOME to Java 25}"
: "${MAVEN_USER_SETTINGS:?Set the path to your Maven settings.xml}"
: "${PAYMENT_TEST_PG_BIN:?Set the PostgreSQL 17 bin directory}"
export JAVA_HOME
export PAYMENT_TEST_PG_BIN
export PAYMENT_TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/payments-native.XXXXXX")"
export PAYMENT_TEST_CACHE="${PAYMENT_TEST_CACHE:-$PAYMENT_TEST_ROOT/m2}"
export PGHOST="$PAYMENT_TEST_ROOT/socket"
export PGPORT="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PY
)"
export PGUSER=payments_repository_owner
export PAYMENTS_NATIVE_PASSWORD="$(openssl rand -hex 32)"
mkdir -p "$PGHOST"
cleanup() {
  if [[ -f "$PAYMENT_TEST_ROOT/data/postmaster.pid" ]]; then
    "$PAYMENT_TEST_PG_BIN/pg_ctl" -D "$PAYMENT_TEST_ROOT/data" -m fast -w stop
  fi
  unset PAYMENTS_NATIVE_PASSWORD
  printf 'Retained owned data and logs: %s\n' "$PAYMENT_TEST_ROOT"
}
trap cleanup EXIT
"$PAYMENT_TEST_PG_BIN/initdb" -D "$PAYMENT_TEST_ROOT/data" -U "$PGUSER" \
  --auth-local=trust --auth-host=scram-sha-256 --no-locale --encoding=UTF8
"$PAYMENT_TEST_PG_BIN/pg_ctl" -D "$PAYMENT_TEST_ROOT/data" \
  -l "$PAYMENT_TEST_ROOT/postgres.log" \
  -o "-k $PGHOST -p $PGPORT -h 127.0.0.1 -c shared_buffers=16MB -c max_connections=10" \
  -w start
python3 - <<'PY'
from pathlib import Path
import hashlib, os, subprocess
paths = sorted(Path('core-banking-payments-models/src/main/resources/db/migration').glob('V*.sql'),
               key=lambda path: int(path.name.split('__')[0][1:]))
assert [int(path.name.split('__')[0][1:]) for path in paths] == list(range(1, 16))
psql = [str(Path(os.environ['PAYMENT_TEST_PG_BIN']) / 'psql'),
        '-X', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1']
for path in paths:
    print(path.name, hashlib.sha256(path.read_bytes()).hexdigest(), flush=True)
    subprocess.run(psql, input='BEGIN;\n' + path.read_text() + '\nCOMMIT;',
                   text=True, check=True)
PY
"$PAYMENT_TEST_PG_BIN/psql" -X -d postgres -v ON_ERROR_STOP=1 <<'SQL'
\getenv native_password PAYMENTS_NATIVE_PASSWORD
CREATE ROLE payments_repository_runtime LOGIN NOSUPERUSER NOBYPASSRLS
  NOCREATEROLE NOCREATEDB PASSWORD :'native_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO payments_repository_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO payments_repository_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payments_repository_runtime;
SELECT rolname,rolsuper,rolbypassrls,rolcreaterole,rolcreatedb
  FROM pg_catalog.pg_roles WHERE rolname='payments_repository_runtime';
SQL
mvn -B -ntp -s "$MAVEN_USER_SETTINGS" \
  -Dmaven.repo.local="$PAYMENT_TEST_CACHE" \
  -DskipOpenApiGen=true -Dopenapi.gen.skip=true \
  -Dtest=ManagedPaymentRepositoryNativeIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpayments.native.port="$PGPORT" \
  -pl core-banking-payments-core -am test
```

The two OpenAPI flags scope this invocation to the explicit repository test; they do not skip its tests. Original producer/SDK qualification remains a separate normal lifecycle without these flags. The role query must show all four privilege flags false. The test independently checks actual context and ordinary pair RLS; grants do not bypass the owner guards. The Surefire selector property permits upstream modules with no matching class, while requiring the named core class to execute. Inspect the fresh report at `core-banking-payments-core/target/surefire-reports/TEST-io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentRepositoryNativeIT.xml`: the current suite has 12 tests and requires zero failures/errors/skips. Verify its timestamp belongs to this invocation. Keep the generated password out of shell tracing, evidence and version control.

The separate web native suites use owned Testcontainers PostgreSQL resources. Configure your existing Docker endpoint without changing the daemon/context, then run `mvn -B -ntp -s "$MAVEN_USER_SETTINGS" -Dmaven.repo.local="$PAYMENT_TEST_CACHE" -pl core-banking-payments-web -am test`. These include 24 quiescence and 29 managed-payment cases at this checkpoint. The full original producer and genuine SDK generation use the normal `verify` lifecycle; do not use a test-only profile or replace generated clients. A multi-service reactor's total is not the count for this single repository.

Actual account/ledger/payment transport and experience/domain journeys are separate qualification gates. They require genuine executable/SDK inputs and explicit current signed authority. Repository or fixture success does not establish V16 reversal, V59 monetary finality, GL/C1, other currencies/products or business account closure.
