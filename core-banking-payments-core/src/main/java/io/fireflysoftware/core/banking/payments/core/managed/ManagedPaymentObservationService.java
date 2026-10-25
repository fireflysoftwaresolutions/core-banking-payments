package io.fireflysoftware.core.banking.payments.core.managed;

import io.fireflysoftware.core.banking.payments.core.managed.LedgerFundsObservationPort.*;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface ManagedPaymentObservationService {
    Mono<View> acknowledge(String tenant, UUID payment, Acknowledge input);
}
