package com.paycenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentRequest {
    @NotBlank(message = "商户ID不能为空")
    private String merchantId;

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    @NotNull(message = "支付金额不能为空")
    @Positive(message = "支付金额必须大于0")
    private BigDecimal amount;

    @NotBlank(message = "支付渠道不能为空")
    private String channel;
}
