package com.datastandard.modules.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigUpdateRequest {

    @NotBlank(message = "配置Key不能为空")
    private String configKey;

    private String configName;

    private String configType;

    @NotNull(message = "配置值不能为空")
    private String configValue;

    private Map<String, Object> configSchema;

    private String description;

    private String scope;

    private Boolean isEnabled;

    private Boolean encrypt;

    private String updatedBy;

    private String changeReason;

    private Map<String, String> tags;
}
