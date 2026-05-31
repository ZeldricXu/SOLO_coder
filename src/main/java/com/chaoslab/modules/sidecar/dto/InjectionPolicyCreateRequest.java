package com.chaoslab.modules.sidecar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class InjectionPolicyCreateRequest {

    @NotBlank(message = "策略名称不能为空")
    private String name;

    @NotBlank(message = "命名空间不能为空")
    private String namespace;

    private Map<String, Object> selector;

    @NotBlank(message = "Sidecar镜像不能为空")
    private String sidecarImage;

    private Map<String, Object> resources;

    private String injectionMode = "automatic";

    private Boolean enabled = true;
}
