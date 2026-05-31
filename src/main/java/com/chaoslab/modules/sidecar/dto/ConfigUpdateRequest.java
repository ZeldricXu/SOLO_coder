package com.chaoslab.modules.sidecar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class ConfigUpdateRequest {

    @NotBlank(message = "实例ID不能为空")
    private String instanceId;

    @NotNull(message = "配置数据不能为空")
    private Map<String, Object> configData;
}
