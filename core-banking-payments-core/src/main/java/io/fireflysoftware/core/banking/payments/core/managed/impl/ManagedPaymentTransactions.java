package io.fireflysoftware.core.banking.payments.core.managed.impl;

import static io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentPolicy.*;
import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import java.util.UUID;
import java.util.function.Supplier;

@Component
final class ManagedPaymentTransactions {
    final ManagedPaymentStore store;
    final ManagedPaymentDatabaseContext context;
    final LedgerFundsObservationPort ledger;
    final TransactionalOperator tx;

    ManagedPaymentTransactions(ManagedPaymentStore store, ManagedPaymentDatabaseContext context,
            LedgerFundsObservationPort ledger, ReactiveTransactionManager manager) {
        this.store = store; this.context = context; this.ledger = ledger;
        this.tx = TransactionalOperator.create(manager);
    }

    <T> Mono<T> transaction(
            Call call, String tenant, String purpose, UUID[] accounts, Supplier<Mono<T>> work) {
        return Mono.defer(
                        () ->
                                context.establish(call, tenant, purpose, accounts)
                                        .then(Mono.defer(work)))
                .as(tx::transactional)
                .onErrorMap(ManagedPaymentTransactions::nativeFenceFailure);
    }

    <T> Mono<T> transaction(
            Call call,
            String tenant,
            String purpose,
            String scope,
            UUID[] accounts,
            Supplier<Mono<T>> work) {
        return Mono.defer(
                        () ->
                                context.establish(call, tenant, purpose, scope, accounts)
                                        .then(Mono.defer(work)))
                .as(tx::transactional)
                .onErrorMap(ManagedPaymentTransactions::nativeFenceFailure);
    }

    private static Throwable nativeFenceFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.r2dbc.spi.R2dbcException sql
                    && "P0001".equals(sql.getSqlState())
                    && "PAYMENT_ACCOUNT_QUIESCED".equals(sql.getMessage()))
                return fail(409, "PAYMENT_ACCOUNT_QUIESCED");
        }
        return error;
    }

}
