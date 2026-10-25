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


package io.fireflysoftware.core.banking.payments.models.entities.instruction;

import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentPriorityEnum;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentStatusEnum;
import io.fireflysoftware.core.banking.payments.models.entities.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("payment_instruction")
public class PaymentInstruction extends BaseEntity {

    @Id
    @Column("payment_instruction_id")
    private UUID paymentInstructionId;

    @Column("payment_order_id")
    private UUID paymentOrderId;

    @Column("instruction_id")
    private String instructionId;

    @Column("instruction_type")
    private String instructionType;

    @Column("instruction_date")
    private java.time.LocalDateTime instructionDate;

    @Column("instruction_status")
    private PaymentStatusEnum instructionStatus;

    @Column("external_reference")
    private String externalReference;

    @Column("instruction_priority")
    private PaymentPriorityEnum instructionPriority;

    @Column("instruction_notes")
    private String instructionNotes;

    @Column("retry_count")
    private Integer retryCount;

    @Column("last_retry_date")
    private java.time.LocalDateTime lastRetryDate;

    @Column("error_code")
    private String errorCode;

    @Column("error_description")
    private String errorDescription;
}
