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


package io.fireflysoftware.core.banking.payments.interfaces.dtos.core;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.BaseDTO;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentFrequencyEnum;
import io.fireflysoftware.core.banking.payments.interfaces.enums.payment.PaymentScheduleStatusEnum;
import org.fireflyframework.utils.annotations.FilterableId;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentScheduleDTO extends BaseDTO {

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID paymentScheduleId;

    @FilterableId
    @NotNull(message = "Payment order ID is required")
    private UUID paymentOrderId;

    @NotNull(message = "Scheduled date is required")
    @Future(message = "Scheduled date must be in the future")
    private LocalDateTime scheduledDate;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @Digits(integer = 16, fraction = 2, message = "Amount must have at most 16 integer digits and 2 decimal places")
    private BigDecimal amount;

    // e.g. "One-time", "Daily", "Weekly", "Monthly", "Yearly"
    @NotNull(message = "Frequency is required")
    private PaymentFrequencyEnum frequency;

    // e.g. "Scheduled", "Completed", "Cancelled"
    @NotNull(message = "Schedule status is required")
    private PaymentScheduleStatusEnum scheduleStatus;

    @Future(message = "End date must be in the future")
    private LocalDate endDate;

    @Min(value = 1, message = "Max executions must be at least 1")
    @Max(value = 9999, message = "Max executions must not exceed 9999")
    private Integer maxExecutions;

    @Min(value = 0, message = "Current execution count must be non-negative")
    private Integer currentExecutionCount;

    private LocalDate nextExecutionDate;

    @Min(value = 1, message = "Day of month must be between 1 and 31")
    @Max(value = 31, message = "Day of month must be between 1 and 31")
    private Integer dayOfMonth;

    @Min(value = 1, message = "Day of week must be between 1 and 7")
    @Max(value = 7, message = "Day of week must be between 1 and 7")
    private Integer dayOfWeek;

    @Min(value = 1, message = "Week of month must be between 1 and 5")
    @Max(value = 5, message = "Week of month must be between 1 and 5")
    private Integer weekOfMonth;

    @Min(value = 1, message = "Month of year must be between 1 and 12")
    @Max(value = 12, message = "Month of year must be between 1 and 12")
    private Integer monthOfYear;

    @Size(max = 500, message = "Schedule notes must not exceed 500 characters")
    private String scheduleNotes;
}