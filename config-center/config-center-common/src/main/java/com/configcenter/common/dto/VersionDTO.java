package com.configcenter.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VersionDTO {
    
    private String versionId;
    private String configId;
    private String version;
    private String configValue;
    private String changeReason;
    private String changedBy;
    private LocalDateTime changedAt;
    private Boolean isRollback;
    private String rollbackFromVersion;
}
