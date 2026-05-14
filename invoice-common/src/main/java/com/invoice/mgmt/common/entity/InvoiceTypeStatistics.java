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
public class InvoiceTypeStatistics {
    private String statId;
    private String statDay;
    private String invoiceType;
    private Integer issueCount;
    private BigDecimal totalAmount;
    private BigDecimal totalTax;
    private Instant createdAt;
    private Instant updatedAt;
}
