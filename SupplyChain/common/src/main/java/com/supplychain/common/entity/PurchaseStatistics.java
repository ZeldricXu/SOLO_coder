package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseStatistics implements Serializable {
    private String statId;
    private String statMonth;
    private Integer orderCount;
    private BigDecimal totalAmount;
    private Integer supplierCount;
}
