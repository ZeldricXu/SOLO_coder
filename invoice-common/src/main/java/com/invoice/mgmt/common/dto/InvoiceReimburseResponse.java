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
public class InvoiceReimburseResponse {
    private String reimburseId;
    private String invoiceId;
    private String status;
    private String reimburseUser;
    private BigDecimal reimburseAmount;
    private String applyTime;
}
