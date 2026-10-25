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


package io.fireflysoftware.core.banking.payments.models.entities.correspondence;

import io.fireflysoftware.core.banking.payments.models.entities.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("payment_correspondence")
public class PaymentCorrespondence extends BaseEntity {

    @Id
    @Column("payment_correspondence_id")
    private UUID paymentCorrespondenceId;

    @Column("payment_order_id")
    private UUID paymentOrderId;

    @Column("correspondence_type")
    private String correspondenceType;

    @Column("correspondence_date")
    private LocalDateTime correspondenceDate;

    @Column("correspondence_channel")
    private String correspondenceChannel;

    @Column("correspondence_direction")
    private String correspondenceDirection; // 'INBOUND' or 'OUTBOUND'

    @Column("correspondent_bank")
    private String correspondentBank;

    @Column("correspondent_reference")
    private String correspondentReference;

    @Column("message_content")
    private String messageContent;

    @Column("attachment_id")
    private UUID attachmentId;
}
