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
public class ConfigHistory {

    private Long id;
    private Long configId;
    private String configKey;
    private String oldValue;
    private String newValue;
    private String oldConfigType;
    private String newConfigType;
    private Boolean oldEnabled;
    private Boolean newEnabled;
    private Integer version;
    private String operationType;
    private String operatedBy;
    private LocalDateTime operatedAt;
    private String changeReason;
    private Map<String, Object> oldSchema;
    private Map<String, Object> newSchema;
    private String source;
    private String rollbackStatus;
    private LocalDateTime rollbackAt;
    private String rollbackBy;
}
