package com.datastandard.modules.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigResponse {

    private Long id;
    private String configKey;
    private String configName;
    private String configType;
    private String configValue;
    private Map<String, Object> configSchema;
    private String description;
    private String scope;
    private Boolean isEnabled;
    private Integer version;
    private String source;
    private Integer priority;
    private Boolean encrypted;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    private Map<String, String> tags;
    private String changeReason;
}
