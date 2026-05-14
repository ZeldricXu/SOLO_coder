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
public class InvoiceStatistics {
    private String statId;
    private String statMonth;
    private Integer issueCount;
    private BigDecimal totalAmount;
    private BigDecimal totalTax;
    private Integer verifyCount;
    private Integer verifyPassCount;
    private Integer reimburseCount;
    private Integer reimburseApproveCount;
    private Instant createdAt;
    private Instant updatedAt;
}
