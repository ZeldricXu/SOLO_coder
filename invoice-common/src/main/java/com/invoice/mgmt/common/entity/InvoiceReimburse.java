package com.invoice.mgmt.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceReimburse {
    private String reimburseId;
    private String invoiceId;
    private String reimburseUser;
    private String reimburseDepartment;
    private BigDecimal reimburseAmount;
    private String reimburseReason;
    private String reimburseStatus;
    private String approver;
    private String approveRemark;
    private Instant applyTime;
    private Instant approveTime;
    private Instant createdAt;
    private Instant updatedAt;
}
