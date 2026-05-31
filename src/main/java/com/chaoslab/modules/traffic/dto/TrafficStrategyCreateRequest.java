package com.chaoslab.modules.traffic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class TrafficStrategyCreateRequest {

    @NotBlank(message = "策略名称不能为空")
    private String name;

    @NotBlank(message = "策略类型不能为空")
    private String type;

    @NotBlank(message = "命名空间不能为空")
    private String namespace;

    private Map<String, Object> selector;

    @NotNull(message = "配置不能为空")
    private Map<String, Object> config;

    private Boolean enabled = false;
}
