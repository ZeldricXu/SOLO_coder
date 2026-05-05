package com.orderflow.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class OrderPayRequest {

    @NotNull(message = "订单ID不能为空")
    private String orderId;

    @NotNull(message = "支付方式不能为空")
    private String paymentMethod;
}
