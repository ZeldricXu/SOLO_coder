package com.taskflow.tenant.model;

import lombok.Data;

import java.util.Map;

@Data
public class TenantConfig {
    private String tenantId;
    private String configKey;
    private String configValue;
    private Map<String, Object> configJson;
    private String description;
}
