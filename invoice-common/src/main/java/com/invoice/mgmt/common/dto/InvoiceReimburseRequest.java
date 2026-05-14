package com.invoice.mgmt.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceReimburseRequest {
    @NotBlank(message = "发票ID不能为空")
    private String invoiceId;

    @NotBlank(message = "报销人不能为空")
    private String reimburseUser;

    private String reimburseDepartment;
    private BigDecimal reimburseAmount;
    private String reimburseReason;
    private String operator;
}
