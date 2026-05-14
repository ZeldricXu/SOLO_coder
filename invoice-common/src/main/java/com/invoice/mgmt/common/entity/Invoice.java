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
public class Invoice {
    private String invoiceId;
    private String invoiceType;
    private String invoiceNo;
    private String invoiceCode;
    private String buyerName;
    private String buyerTaxNo;
    private String sellerName;
    private String sellerTaxNo;
    private BigDecimal invoiceAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String invoiceStatus;
    private Instant issueTime;
    private Instant createdAt;
    private Instant updatedAt;
}
