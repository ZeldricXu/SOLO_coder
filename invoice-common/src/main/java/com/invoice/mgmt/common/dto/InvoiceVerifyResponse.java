package com.invoice.mgmt.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceVerifyResponse {
    private String verifyId;
    private String verifyResult;
    private String verifyType;
    private String verifiedAt;
    private String verifyDetail;
}
