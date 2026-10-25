/*
 * Copyright 2026 Firefly Software Foundation
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

package io.fireflysoftware.core.banking.payments.core.managed.impl;

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.core.banking.ledger.sdk.model.Account;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

class SdkLedgerFundsClosureObservationTest {
    final UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
    final UUID enrollment = UUID.fromString("20000000-0000-0000-0000-000000000001");
    final UUID contract = UUID.fromString("30000000-0000-0000-0000-000000000001");

    Account account() {
        return new Account()
                .accountId(id)
                .enrollmentId(enrollment)
                .contractId(contract)
                .currency("USD")
                .currencyScale(2)
                .currencyPolicy("USD-2026")
                .version(7L)
                .kind(Account.KindEnum.CUSTOMER)
                .state(Account.StateEnum.BLOCKED);
    }

    @Test
    void blockedAccountRetainsTrueLedgerTupleForRecovery() {
        var result = SdkLedgerFundsObservationAdapter.closureTuple("bank", id, account());
        assertThat(result.accountId()).isEqualTo(id);
        assertThat(result.enrollmentId()).isEqualTo(enrollment);
        assertThat(result.contractId()).isEqualTo(contract);
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.currencyScale()).isEqualTo(2);
        assertThat(result.currencyPolicy()).isEqualTo("USD-2026");
        assertThat(result.ledgerVersion()).isEqualTo(7);
        assertThat(result.state()).isEqualTo("BLOCKED");
    }

    @Test
    void missingEnrollmentCannotBecomeQualifiedTuple() {
        denied(account().enrollmentId(null));
    }

    @Test
    void missingContractCannotBecomeQualifiedTuple() {
        denied(account().contractId(null));
    }

    @Test
    void mismatchedAccountCannotBecomeQualifiedTuple() {
        denied(account().accountId(contract));
    }

    @Test
    void negativeRevisionCannotBecomeQualifiedTuple() {
        denied(account().version(-1L));
    }

    @Test
    void missingCurrencyPolicyCannotBecomeQualifiedTuple() {
        denied(account().currencyPolicy(null));
    }

    @Test
    void excessiveScaleCannotBecomeQualifiedTuple() {
        denied(account().currencyScale(19));
    }

    void denied(Account value) {
        assertThatThrownBy(() -> SdkLedgerFundsObservationAdapter.closureTuple("bank", id, value))
                .isInstanceOf(ResponseStatusException.class);
    }
}
