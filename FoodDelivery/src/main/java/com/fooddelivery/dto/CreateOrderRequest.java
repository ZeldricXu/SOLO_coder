package com.fooddelivery.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {
    @NotBlank(message = "餐厅ID不能为空")
    private String restaurant_id;

    @NotEmpty(message = "订单项不能为空")
    private List<OrderItemDto> order_items;

    @NotBlank(message = "配送地址不能为空")
    private String delivery_address;

    private String user_id;

    private String order_urgency;
}
