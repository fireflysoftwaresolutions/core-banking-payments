package io.fireflysoftware.core.banking.payments.core.managed;

import static org.assertj.core.api.Assertions.*;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.ManagedPaymentNativeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.repository.Query;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Source identity only; PostgreSQL execution and catalog behavior require separate native qualification. */
class ManagedPaymentCanonicalReadinessSourceTest {
    private final Path migrations = Path.of("../core-banking-payments-models/src/main/resources/db/migration");

    @Test
    void readinessQueryPinsExactRuntimeDollarBodyAndActualOwnerIdentity() throws Exception {
        String body = runtimeBody(Files.readString(migrations.resolve("V17__Bind_managed_payment_receipt_values.sql")));
        String query = query();
        assertThat(query).contains("'" + digest(body) + "'",
                "SELECT public.managed_payment_repository_runtime() AND EXISTS",
                "n.nspname='public'", "p.pronargs=0", "p.proargtypes=''::pg_catalog.oidvector",
                "p.proowner=c.relowner", "cn.nspname='public'", "c.relname='managed_payment_monetary_proof'",
                "NOT p.prosecdef", "p.prorettype='pg_catalog.bool'::pg_catalog.regtype",
                "pg_catalog.sha256(pg_catalog.convert_to(p.prosrc,'UTF8'))");
    }

    @Test
    void oldRuntimeBodyAndOneByteChangesCannotMatchTheReviewedSourceIdentity() throws Exception {
        String body = runtimeBody(Files.readString(migrations.resolve("V17__Bind_managed_payment_receipt_values.sql")));
        String old = runtimeBody(Files.readString(migrations.resolve("V16__Managed_payment_reversal.sql")));
        assertThat(query()).doesNotContain("'" + digest(old) + "'", "'" + digest(body + " ") + "'");
        assertThat(digest(body)).isNotEqualTo(digest(old)).isNotEqualTo(digest(body + " "));
    }

    private String query() throws Exception {
        return ManagedPaymentNativeRepository.class.getMethod("runtimeAllowed").getAnnotation(Query.class).value();
    }

    private String runtimeBody(String sql) {
        int function = sql.indexOf("CREATE OR REPLACE FUNCTION public.managed_payment_repository_runtime()");
        assertThat(function).isGreaterThanOrEqualTo(0);
        int start = sql.indexOf("$repository$", function) + "$repository$".length();
        return sql.substring(start, sql.indexOf("$repository$;", start));
    }

    private String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
