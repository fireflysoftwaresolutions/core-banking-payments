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


package io.fireflysoftware.core.banking.payments.models.entities.core;

import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentFrequencyEnum;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentScheduleStatusEnum;
import io.fireflysoftware.core.banking.payments.models.entities.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("payment_schedule")
public class PaymentSchedule extends BaseEntity {

    @Id
    @Column("payment_schedule_id")
    private UUID paymentScheduleId;

    @Column("payment_order_id")
    private UUID paymentOrderId;

    @Column("scheduled_date")
    private LocalDateTime scheduledDate;

    @Column("amount")
    private BigDecimal amount;

    @Column("frequency")
    private PaymentFrequencyEnum frequency;

    @Column("schedule_status")
    private PaymentScheduleStatusEnum scheduleStatus;

    @Column("end_date")
    private LocalDate endDate;

    @Column("max_executions")
    private Integer maxExecutions;

    @Column("current_execution_count")
    private Integer currentExecutionCount;

    @Column("next_execution_date")
    private LocalDate nextExecutionDate;

    @Column("day_of_month")
    private Integer dayOfMonth;

    @Column("day_of_week")
    private Integer dayOfWeek;

    @Column("week_of_month")
    private Integer weekOfMonth;

    @Column("month_of_year")
    private Integer monthOfYear;

    @Column("schedule_notes")
    private String scheduleNotes;
}