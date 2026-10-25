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


package io.fireflysoftware.core.banking.payments.models.entities.audit;

import io.fireflysoftware.core.banking.payments.models.entities.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("payment_audit")
public class PaymentAudit extends BaseEntity {

    @Id
    @Column("payment_audit_id")
    private UUID paymentAuditId;

    @Column("payment_order_id")
    private UUID paymentOrderId;

    @Column("payment_instruction_id")
    private UUID paymentInstructionId;

    @Column("action")
    private String action;

    @Column("action_date")
    private LocalDateTime actionDate;

    @Column("action_by")
    private String actionBy;

    @Column("previous_status")
    private String previousStatus;

    @Column("new_status")
    private String newStatus;

    @Column("ip_address")
    private String ipAddress;

    @Column("user_agent")
    private String userAgent;

    @Column("additional_info")
    private String additionalInfo; // JSONB in the database
}
