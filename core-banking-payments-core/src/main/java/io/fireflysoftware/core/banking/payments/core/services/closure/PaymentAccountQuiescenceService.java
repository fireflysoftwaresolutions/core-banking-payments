package io.fireflysoftware.core.banking.payments.core.services.closure;

import static io.fireflysoftware.core.banking.payments.interfaces.dtos.closure.PaymentAccountQuiescenceContracts.*;

import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.function.Supplier;

public interface PaymentAccountQuiescenceService {
    Mono<QuiescenceReceipt> quiesce(String tenant, UUID account, byte[] raw);
    Mono<View> get(String tenant, UUID account, UUID closure);
    Mono<ObligationPage> obligations(String tenant, UUID account, UUID closure, UUID observation, long after, int limit);
    Mono<ReleaseReceipt> release(String tenant, UUID account, byte[] raw);
}
