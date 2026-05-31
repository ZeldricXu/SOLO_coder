package com.iotplatform.config.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class ConfigCreateDTO {

    @NotBlank(message = "配置ID不能为空")
    private String configId;

    private String namespace = "default";

    @NotBlank(message = "配置键不能为空")
    private String configKey;

    @NotBlank(message = "配置值不能为空")
    private String configValue;

    private String description;

    private Boolean enabled = true;

    private String createdBy;

    private Map<String, Object> parameters;
}
