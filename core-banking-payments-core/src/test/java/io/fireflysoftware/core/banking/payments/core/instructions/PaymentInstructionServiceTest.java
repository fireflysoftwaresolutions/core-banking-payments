/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.fireflysoftware.core.banking.payments.core.instructions;

import io.fireflysoftware.core.banking.payments.core.instructions.impl.PaymentInstructionServiceImpl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentInstructionServiceTest {

    private final PaymentInstructionService service = new PaymentInstructionServiceImpl(new SandboxPaymentTccAdapter());

    @Test
    void activeBeneficiaryIsPaidAndInactiveIsRefused() {
        assertThat(service.payBeneficiary(PaymentInstructions.BeneficiaryStatus.ACTIVE,
                new BigDecimal("12.00"), "USD", "ben-1").block()).isNotNull();
        assertThatThrownBy(() -> service.payBeneficiary(PaymentInstructions.BeneficiaryStatus.INACTIVE,
                new BigDecimal("12.00"), "USD", "ben-2").block())
                .hasMessageContaining("Inactive beneficiary");
    }

    @Test
    void standingOrderAdvancesItsNextDateAfterConfirm() {
        PaymentInstructions.StandingOrder due = new PaymentInstructions.StandingOrder(
                LocalDate.of(2026, 10, 1), null, 0, 12, new BigDecimal("30.00"), "EUR");
        PaymentInstructions.StandingOrder next = service.runStandingOrder(due, LocalDate.of(2026, 10, 1), "so-1").block();
        assertThat(next.nextExecution()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(next.executions()).isEqualTo(1);
    }

    @Test
    void directDebitCollectsInsideTheMandateAndStopsAboveIt() {
        assertThat(service.collectDirectDebit(new PaymentInstructions.DirectDebit(
                PaymentInstructions.MandateStatus.ACTIVE, new BigDecimal("100.00"), "GBP", new BigDecimal("40.00")),
                "dd-1").block()).isNotNull();
        assertThatThrownBy(() -> service.collectDirectDebit(new PaymentInstructions.DirectDebit(
                PaymentInstructions.MandateStatus.ACTIVE, new BigDecimal("10.00"), "GBP", new BigDecimal("40.00")),
                "dd-2").block()).hasMessageContaining("mandate limit");
        assertThatThrownBy(() -> service.collectDirectDebit(new PaymentInstructions.DirectDebit(
                PaymentInstructions.MandateStatus.SUSPENDED, new BigDecimal("100.00"), "GBP", new BigDecimal("40.00")),
                "dd-3").block()).hasMessageContaining("not active");
    }
}
