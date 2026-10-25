package io.fireflysoftware.core.banking.payments.core.managed;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.*;

public interface ManagedPaymentCoordinatorService {
    Mono<EffectClaim> claim(String tenant, UUID payment, Claim input);
    Mono<View> recordFailure(String tenant, UUID payment, Failure input);
    Mono<RecoveryPage> recoveryQueue(String tenant, UUID after, int limit);
    Mono<View> decide(String tenant, UUID payment, Decision input);
    Mono<View> recover(String tenant, UUID payment, Recovery input);
}
