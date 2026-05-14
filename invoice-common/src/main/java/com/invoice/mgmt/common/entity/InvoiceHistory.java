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
public class InvoiceHistory {
    private Long id;
    private String invoiceId;
    private String actionType;
    private String actionContent;
    private String operator;
    private Instant actionTime;
    private Instant createdAt;
}
