package com.chaoslab.modules.sidecar.dto;

import lombok.Data;

import java.util.Map;

@Data
public class DynamicConfigCreateRequest {
    private String configKey;
    private String configName;
    private String configType;
    private String description;
    private Map<String, Object> configValue;
    private String defaultValue;
    private String validationRule;
    private Boolean hotReloadable;
    private String scope;
}
