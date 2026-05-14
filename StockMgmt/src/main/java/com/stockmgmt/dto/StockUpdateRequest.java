package com.stockmgmt.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StockUpdateRequest {

    private String productName;

    private String skuId;

    private String locationId;

    private String unit;

    private BigDecimal costPrice;

    private Integer warningThreshold;

    private Integer overstockThreshold;
}
