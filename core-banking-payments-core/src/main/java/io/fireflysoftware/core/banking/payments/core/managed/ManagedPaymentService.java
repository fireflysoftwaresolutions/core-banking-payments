package io.fireflysoftware.core.banking.payments.core.managed;

import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.EnrolledPair;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.*;
import java.util.*;
import java.util.function.Supplier;

public interface ManagedPaymentService {
    record Accepted(View payment, boolean replayed) {}
    Mono<Accepted> create(String tenant, Create input);
    Mono<View> read(String tenant, UUID id);
    Flux<HistoryEvent> history(String tenant, UUID id, long after, int limit);
}
