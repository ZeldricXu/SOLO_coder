package com.device.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BackupRequest {
    @NotBlank(message = "backupType不能为空")
    private String backupType;

    private String backupScope;
    private Integer retentionDays;
    private boolean encrypted;
}
