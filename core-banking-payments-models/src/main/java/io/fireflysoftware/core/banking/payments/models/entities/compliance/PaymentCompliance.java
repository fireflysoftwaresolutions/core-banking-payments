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


package io.fireflysoftware.core.banking.payments.models.entities.compliance;

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
@Table("payment_compliance")
public class PaymentCompliance extends BaseEntity {

    @Id
    @Column("payment_compliance_id")
    private UUID paymentComplianceId;

    @Column("payment_order_id")
    private UUID paymentOrderId;

    @Column("screening_status")
    private String screeningStatus;

    @Column("screening_date")
    private LocalDateTime screeningDate;

    @Column("screening_reference")
    private String screeningReference;

    @Column("risk_score")
    private Integer riskScore;

    @Column("risk_level")
    private String riskLevel;

    @Column("compliance_notes")
    private String complianceNotes;

    @Column("aml_check_status")
    private String amlCheckStatus;

    @Column("sanctions_check_status")
    private String sanctionsCheckStatus;

    @Column("pep_check_status")
    private String pepCheckStatus;

    @Column("kyc_check_status")
    private String kycCheckStatus;

    @Column("approved_by")
    private String approvedBy;

    @Column("approval_date")
    private LocalDateTime approvalDate;

    @Column("rejection_reason")
    private String rejectionReason;
}
