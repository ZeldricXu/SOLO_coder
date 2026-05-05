package com.orderflow.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class RefundApplyRequest {

    @NotNull(message = "订单ID不能为空")
    private String orderId;

    @NotNull(message = "退款金额不能为空")
    private BigDecimal refundAmount;

    @NotNull(message = "退款原因不能为空")
    private String refundReason;
}
