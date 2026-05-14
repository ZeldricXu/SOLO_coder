package com.supplychain.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventorySyncRequest implements Serializable {
    private String supplierId;
    private String syncType;
    private Map<String, Map<String, Object>> syncData;
}
