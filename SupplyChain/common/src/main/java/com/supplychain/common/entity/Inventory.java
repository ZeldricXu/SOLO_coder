package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Inventory implements Serializable {
    private String inventoryId;
    private String itemId;
    private String itemName;
    private String supplierId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private Integer warningThreshold;
    private LocalDateTime lastSyncTime;
    private LocalDateTime updatedAt;
}
