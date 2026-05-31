package com.iotplatform.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfigRollbackDTO {

    @NotBlank(message = "配置ID不能为空")
    private String configId;

    private String namespace = "default";

    @NotNull(message = "目标版本不能为空")
    private Integer targetVersion;

    private String rolledBackBy;
}
