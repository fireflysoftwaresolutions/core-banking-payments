package io.fireflysoftware.core.banking.payments.core.services.debit;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.debit.DirectDebitMandateDTO;
import io.fireflysoftware.core.banking.payments.interfaces.enums.debit.DirectDebitMandateStatus;
import io.fireflysoftware.core.banking.payments.models.entities.debit.DirectDebitMandate;
import io.fireflysoftware.core.banking.payments.models.repositories.debit.DirectDebitMandateRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface DirectDebitMandateService {
    Mono<DirectDebitMandateDTO> create(DirectDebitMandateDTO request);
    Mono<DirectDebitMandateDTO> get(UUID mandateId);
}
