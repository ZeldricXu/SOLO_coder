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
public class InvoiceVerify {
    private String verifyId;
    private String invoiceId;
    private String verifyType;
    private String verifyResult;
    private String verifySource;
    private String verifyDetail;
    private Instant verifiedAt;
    private Instant createdAt;
}
