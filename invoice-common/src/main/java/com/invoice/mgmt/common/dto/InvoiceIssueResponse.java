package com.invoice.mgmt.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceIssueResponse {
    private String invoiceId;
    private String invoiceNo;
    private String invoiceCode;
    private String invoiceStatus;
    private String issueTime;
}
