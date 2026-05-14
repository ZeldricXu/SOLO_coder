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
public class InvoiceStatusLog {
    private Long id;
    private String invoiceId;
    private String previousStatus;
    private String currentStatus;
    private String operator;
    private String remark;
    private Instant changeTime;
    private Instant createdAt;
}
