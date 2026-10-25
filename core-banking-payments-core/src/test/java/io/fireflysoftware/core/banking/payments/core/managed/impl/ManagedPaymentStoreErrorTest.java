package io.fireflysoftware.core.banking.payments.core.managed.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.View;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import io.r2dbc.postgresql.api.*;
import io.r2dbc.postgresql.message.backend.Field;
import io.r2dbc.spi.R2dbcException;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.*;

class ManagedPaymentStoreErrorTest {
    private final ManagedPaymentJournalRepository journal = mock(ManagedPaymentJournalRepository.class);
    private final ManagedPaymentStore store = new ManagedPaymentStore(mock(ManagedPaymentOperationRepository.class),
            mock(ManagedPaymentActionRepository.class), mock(ManagedPaymentEffectRepository.class),
            mock(ManagedPaymentHistoryRepository.class), journal, mock(ManagedPaymentNativeRepository.class));

    private static class NativeFailure extends R2dbcException implements PostgresqlException {
        private final ErrorDetails details;
        NativeFailure(String code, String constraint) {
            super("owned native error", code, 0);
            details = new ErrorDetails(List.of(new Field(Field.FieldType.CODE, code),
                    new Field(Field.FieldType.MESSAGE, "owned native error"),
                    new Field(Field.FieldType.CONSTRAINT_NAME, constraint)));
        }
        public ErrorDetails getErrorDetails() { return details; }
    }

    private Mono<Void> attempt(Throwable failure) {
        View view = mock(View.class);
        when(view.tenant()).thenReturn("bank");
        when(view.paymentId()).thenReturn(UUID.randomUUID());
        when(view.sourceAccountId()).thenReturn(UUID.randomUUID());
        when(view.destinationAccountId()).thenReturn(UUID.randomUUID());
        when(journal.insertAction(any())).thenReturn(Mono.error(failure));
        return store.insertAction(view, UUID.randomUUID(), "CONFIRM", "a".repeat(64), "actor", null);
    }

    @Test
    void onlyActualActionPrimaryKeyUniqueViolationMapsToConflict() {
        var failure = new org.springframework.dao.DataIntegrityViolationException("wrapped",
                new NativeFailure("23505", "managed_payment_action_pkey"));
        StepVerifier.create(attempt(failure)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(409);
            assertThat(((ResponseStatusException) error).getReason()).isEqualTo("PAYMENT_ACTION_CONFLICT");
        }).verify();
    }

    @Test
    void unrelatedUniqueConstraintIsNotRelabeled() {
        var failure = new NativeFailure("23505", "managed_payment_effect_pkey");
        StepVerifier.create(attempt(failure)).expectErrorMatches(error -> error == failure).verify();
    }

    @Test
    void unrelatedSqlStateIsNotRelabeledEvenWithTheSameConstraintName() {
        var failure = new NativeFailure("23514", "managed_payment_action_pkey");
        StepVerifier.create(attempt(failure)).expectErrorMatches(error -> error == failure).verify();
    }

    @Test
    void messageTextCannotImpersonateDriverConstraintMetadata() {
        var failure = new IllegalStateException("23505 managed_payment_action_pkey");
        StepVerifier.create(attempt(failure)).expectErrorMatches(error -> error == failure).verify();
    }
}
