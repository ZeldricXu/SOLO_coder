package com.paycenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RefundRequest {
    @NotBlank(message = "交易ID不能为空")
    private String transactionId;

    @NotNull(message = "退款金额不能为空")
    @Positive(message = "退款金额必须大于0")
    private BigDecimal refundAmount;

    private String refundReason;
}
