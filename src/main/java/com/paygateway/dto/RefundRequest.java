package com.paygateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RefundRequest {
    
    @NotBlank(message = "商户ID不能为空")
    private String merchantId;
    
    private String gatewayOrderId;
    private String merchantOrderNo;
    
    @NotBlank(message = "商户退款单号不能为空")
    private String merchantRefundNo;
    
    @NotNull(message = "退款金额不能为空")
    @Positive(message = "退款金额必须大于0")
    private BigDecimal amount;
    
    private String reason;
}
