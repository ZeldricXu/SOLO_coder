package com.invoice.mgmt.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceIssueRequest {
    @NotBlank(message = "发票类型不能为空")
    private String invoiceType;

    @NotBlank(message = "购买方名称不能为空")
    private String buyerName;

    private String buyerTaxNo;

    @NotNull(message = "发票金额不能为空")
    private BigDecimal invoiceAmount;

    private String sellerName;
    private String sellerTaxNo;
    private String operator;
}
