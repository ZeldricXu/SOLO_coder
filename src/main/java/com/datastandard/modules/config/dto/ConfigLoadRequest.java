package com.datastandard.modules.config.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigLoadRequest {

    @NotBlank(message = "配置Key不能为空")
    private String configKey;

    private String scope;

    private List<String> configKeys;

    private List<String> sources;

    private Map<String, String> context;

    private Boolean decrypt;

    private String version;

    private Boolean includeInactive;
}
