package com.supplychain.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemRequest implements Serializable {
    private String itemId;
    private String itemName;
    private Integer quantity;
    private java.math.BigDecimal price;
}
