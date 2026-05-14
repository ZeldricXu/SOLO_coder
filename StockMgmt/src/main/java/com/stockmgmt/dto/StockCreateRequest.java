package com.stockmgmt.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;

@Data
public class StockCreateRequest {

    @NotBlank(message = "商品ID不能为空")
    private String productId;

    private String productName;

    private String skuId;

    private String warehouseId;

    private String locationId;

    private String unit;

    private BigDecimal costPrice;

    private Integer warningThreshold = 10;

    private Integer overstockThreshold = 500;
}
