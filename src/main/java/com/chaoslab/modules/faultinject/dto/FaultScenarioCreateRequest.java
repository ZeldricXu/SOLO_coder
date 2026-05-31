package com.chaoslab.modules.faultinject.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FaultScenarioCreateRequest {

    @NotBlank(message = "场景名称不能为空")
    private String name;

    private String description;

    @NotBlank(message = "故障类型不能为空")
    private String faultType;

    @NotNull(message = "注入范围不能为空")
    private Map<String, Object> scope;

    @NotNull(message = "故障配置不能为空")
    private Map<String, Object> config;

    private Long durationMs;

    private Boolean autoRollback = true;

    private Long rollbackTimeoutMs = 300000L;

    private List<String> tags;

    private Boolean enabled = true;
}
