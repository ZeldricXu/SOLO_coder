package com.configcenter.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RollbackConfigRequest {
    
    @NotBlank(message = "配置ID不能为空")
    private String configId;

    @NotBlank(message = "目标版本不能为空")
    private String targetVersion;

    private String rollbackReason;

    private Boolean autoPush = true;

    private String operator = "system";
}
