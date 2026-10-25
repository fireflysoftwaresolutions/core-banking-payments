package io.fireflysoftware.core.banking.payments.core.instructions;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface PaymentInstructionService {
    Mono<UUID> payBeneficiary(PaymentInstructions.BeneficiaryStatus status, BigDecimal amount, String currency, String idempotencyKey);
    Mono<PaymentInstructions.StandingOrder> runStandingOrder(PaymentInstructions.StandingOrder order, LocalDate today, String idempotencyKey);
    Mono<UUID> collectDirectDebit(PaymentInstructions.DirectDebit debit, String idempotencyKey);
}
