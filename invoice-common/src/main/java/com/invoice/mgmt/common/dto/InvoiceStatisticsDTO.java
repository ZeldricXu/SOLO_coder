package com.invoice.mgmt.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceStatisticsDTO {
    private String statMonth;
    private Integer issueCount;
    private BigDecimal totalAmount;
    private BigDecimal totalTax;
    private Integer verifyCount;
    private Integer verifyPassCount;
    private Integer reimburseCount;
    private Integer reimburseApproveCount;
}
