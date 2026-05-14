package com.invoice.mgmt.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceNumber {
    private Long id;
    private String invoiceType;
    private String invoiceCode;
    private String startNo;
    private String endNo;
    private String currentNo;
    private Integer totalCount;
    private Integer usedCount;
    private Integer remainingCount;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
