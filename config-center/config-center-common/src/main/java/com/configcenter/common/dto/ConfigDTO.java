package com.configcenter.common.dto;

import com.configcenter.common.enums.ConfigType;
import com.configcenter.common.enums.Environment;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConfigDTO {
    
    private String configId;
    private String configKey;
    private String configValue;
    private ConfigType configType;
    private Boolean isEncrypted;
    private Environment environment;
    private String groupId;
    private String description;
    private String currentVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
}
