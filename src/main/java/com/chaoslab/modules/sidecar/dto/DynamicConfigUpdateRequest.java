package com.chaoslab.modules.sidecar.dto;

import lombok.Data;

import java.util.Map;

@Data
public class DynamicConfigUpdateRequest {
    private String configId;
    private Map<String, Object> configValue;
    private String changeReason;
    private String changedBy;
}
