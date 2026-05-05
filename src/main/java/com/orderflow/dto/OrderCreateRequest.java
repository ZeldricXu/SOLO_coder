package com.orderflow.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class OrderCreateRequest {

    @NotNull(message = "用户ID不能为空")
    private String userId;

    @NotEmpty(message = "订单项不能为空")
    @Valid
    private List<OrderItemRequest> items;

    @NotNull(message = "支付方式不能为空")
    private String paymentMethod;
}
