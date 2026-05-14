package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventorySync implements Serializable {
    private String syncId;
    private String supplierId;
    private String syncType;
    private Map<String, Map<String, Object>> syncData;
    private LocalDateTime syncTime;
}
