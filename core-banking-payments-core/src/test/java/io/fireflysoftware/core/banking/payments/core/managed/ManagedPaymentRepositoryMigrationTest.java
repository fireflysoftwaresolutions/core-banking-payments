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
        assertThat(migrations.resolve("V16__Managed_payment_reversal.sql")).doesNotExist();
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
}
