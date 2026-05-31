package com.chaoslab.modules.sidecar.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ConfigTemplateCreateRequest {
    private String templateName;
    private String templateType;
    private String scenario;
    private String description;
    private Map<String, Object> configData;
    private Map<String, Object> resourceLimits;
    private Integer priority;
}
