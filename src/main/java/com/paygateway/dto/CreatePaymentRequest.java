package com.paygateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreatePaymentRequest {
    
    @NotBlank(message = "商户ID不能为空")
    private String merchantId;
    
    @NotBlank(message = "商户订单号不能为空")
    private String merchantOrderNo;
    
    @NotNull(message = "订单金额不能为空")
    @Positive(message = "订单金额必须大于0")
    private BigDecimal amount;
    
    @NotBlank(message = "支付渠道不能为空")
    private String channel;
    
    private String productDesc;
    
    private String notifyUrl;
}
