package io.fireflysoftware.core.banking.payments.core.managed;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

class ManagedPaymentRepositoryMigrationTest {
    private final Path migrations = Path.of("../core-banking-payments-models/src/main/resources/db/migration");

    @Test
    void additiveRepositoryCapabilitiesCannotRewriteAcceptedGuardsOrIntroduceADefiner() throws Exception {
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(
                migrations.resolve("V12__Managed_internal_payments.sql")))))
                .isEqualTo("4a48f2db8648af6d5ed43e543f5b901337a5eed603685428ed7f5e074e6c62e8");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(
                migrations.resolve("V14__Create_account_payment_quiescence.sql")))))
                .isEqualTo("924fd6e65119bd9c76efc0c6fc8a822f4699a17b693b3c32ff31ef881961f784");
        Path support = migrations.resolve("V15__Managed_payment_repository_functions.sql");
        assertThat(support).isRegularFile();
        String sql = Files.readString(support);
        assertThat(sql).doesNotContain("SECURITY DEFINER", "CREATE TABLE", "CREATE POLICY", "ALTER TABLE");
        assertThat(sql.split("SECURITY INVOKER", -1)).hasSize(11);
        assertThat(migrations.resolve("V16__Managed_payment_reversal.sql")).isRegularFile();
    }
    @Test
    void closureAttestationCatalogueNamesRemainTheOriginalPgClassRelnames() throws Exception {
        String sql = Files.readString(migrations.resolve("V15__Managed_payment_repository_functions.sql"));
        int start = sql.indexOf("CREATE FUNCTION public.payment_account_repository_runtime");
        assertThat(start).isGreaterThanOrEqualTo(0);
        String function = sql.substring(start, sql.indexOf("$repository$;", start));
        String catalogueNames = function.substring(function.indexOf("VALUES "), function.indexOf("relations AS"));
        assertThat(catalogueNames).contains("('managed_payment_operation')", "('managed_payment_effect')",
                "('managed_payment_account_observation')").doesNotContain("'public.managed_payment_");
    }

    @Test
    void linkedReversalProjectsExactQualifiedInvokerGraphWithoutChangingPriorRepositoryMigration() throws Exception {
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(
                migrations.resolve("V15__Managed_payment_repository_functions.sql")))))
                .isEqualTo("44bbc474fb14f66dcebedb94822e15315a56ed84a1a30e1ad0b4f6e303aa6fe1");
        Path linked = migrations.resolve("V16__Managed_payment_reversal.sql");
        assertThat(linked).isRegularFile();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(linked))))
                .isEqualTo("8c0fc8d7287b8b757bc3c2dff91fd98d957b44f787366370f71f67c52ef4e2b4");
        String sql = Files.readString(linked);
        assertThat(sql).contains("CREATE TABLE public.managed_payment_monetary_proof", "count(*)=15",
                "managed_reversal_operation_complete", "managed_reversal_effect_complete", "managed_reversal_proof_complete",
                "managed_reversal_action_complete", "managed_reversal_history_complete", "managed_reversal_outbox_complete")
                .doesNotContain("SECURITY DEFINER", "REVOKE ", "GRANT ", "CREATE ROLE");
        assertThat(sql.split("SECURITY INVOKER", -1)).hasSize(5);
        assertThat(sql.lines().filter(line -> line.contains("NOT pg_catalog.has_database_privilege(current_user,current_database(),'CREATE')")).count()).isEqualTo(2);
        assertThat(sql.lines().filter(line -> line.contains("NOT pg_catalog.has_database_privilege(current_user,current_database(),'TEMP')")).count()).isEqualTo(2);
    }

    @Test
    void actualManagedAttestationRetainsEveryClosureRoleAndProtectedRelationCheck() throws Exception {
        String sql = Files.readString(migrations.resolve("V16__Managed_payment_reversal.sql"));
        String managed = body(sql,"managed_payment_repository_runtime() RETURNS boolean");
        String closure = body(sql,"payment_account_repository_runtime(valid_until bigint) RETURNS boolean")
                .replace(" AND $1>floor(extract(epoch FROM clock_timestamp())*1000)::bigint", "");
        assertThat(closure).contains("NOT r.rolcreaterole", "NOT r.rolcreatedb", "count(*)=15",
                "relrowsecurity AND relforcerowsecurity AND NOT pg_has_role(current_user,relowner,'MEMBER')",
                "p.proowner='payment_closure_guard'::regrole");
        assertThat(managed).isEqualTo(closure);
    }

    private String body(String sql,String signature) {
        int start = sql.indexOf("CREATE OR REPLACE FUNCTION public."+signature);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int body = sql.indexOf("$repository$",start)+"$repository$".length();
        return sql.substring(body,sql.indexOf("$repository$;",body));
    }
}
