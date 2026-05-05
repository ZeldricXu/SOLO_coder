package com.orderflow.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class OrderItemRequest {

    @NotNull(message = "商品ID不能为空")
    private String productId;

    @NotNull(message = "商品数量不能为空")
    @Min(value = 1, message = "商品数量至少为1")
    private Integer quantity;

    @NotNull(message = "商品价格不能为空")
    private BigDecimal price;
}
