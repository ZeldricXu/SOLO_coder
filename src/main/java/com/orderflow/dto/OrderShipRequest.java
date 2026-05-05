package com.orderflow.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class OrderShipRequest {

    @NotNull(message = "订单ID不能为空")
    private String orderId;

    @NotNull(message = "承运商不能为空")
    private String carrier;

    @NotNull(message = "运单号不能为空")
    private String trackingNo;
}
