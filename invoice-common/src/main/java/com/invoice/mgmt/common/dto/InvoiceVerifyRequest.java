package com.invoice.mgmt.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceVerifyRequest {
    @NotBlank(message = "发票号码不能为空")
    private String invoiceNo;

    @NotBlank(message = "发票代码不能为空")
    private String invoiceCode;

    private String verifyType = "online";
    private String operator;
}
