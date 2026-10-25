package io.fireflysoftware.core.banking.payments.core.services.debit;

import io.fireflysoftware.core.banking.payments.core.instructions.PaymentInstructions;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.debit.DirectDebitCollectionDTO;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.debit.StandingOrderRunDTO;
import io.fireflysoftware.core.banking.payments.models.entities.debit.DirectDebitCollection;
import io.fireflysoftware.core.banking.payments.models.entities.debit.StandingOrderRun;
import io.fireflysoftware.core.banking.payments.models.repositories.debit.DirectDebitCollectionRepository;
import io.fireflysoftware.core.banking.payments.models.repositories.debit.DirectDebitMandateRepository;
import io.fireflysoftware.core.banking.payments.models.repositories.debit.StandingOrderRunRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface DirectDebitCollectionService {
    Mono<DirectDebitCollectionDTO> collect(DirectDebitCollectionDTO request);
    Mono<DirectDebitCollectionDTO> getCollection(UUID collectionId);
    Mono<StandingOrderRunDTO> runStandingOrder(StandingOrderRunDTO request);
    Mono<StandingOrderRunDTO> getRun(UUID runId);
}
