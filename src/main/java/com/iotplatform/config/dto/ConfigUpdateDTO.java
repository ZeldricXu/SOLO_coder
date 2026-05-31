package com.iotplatform.config.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class ConfigUpdateDTO {

    @NotBlank(message = "配置ID不能为空")
    private String configId;

    private String namespace = "default";

    private String configValue;

    private String description;

    private Boolean enabled;

    private String updatedBy;

    private Map<String, Object> parameters;
}
