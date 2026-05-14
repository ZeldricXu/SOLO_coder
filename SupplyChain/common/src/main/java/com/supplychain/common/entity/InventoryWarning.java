package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryWarning implements Serializable {
    private String warningId;
    private String itemId;
    private String warningType;
    private String warningLevel;
    private Integer currentQuantity;
    private Integer warningThreshold;
    private LocalDateTime triggeredAt;
    private String status;
    private String handler;
    private LocalDateTime handledAt;
}
