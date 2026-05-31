package com.iotplatform.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class RouteDefinition {

    @NotBlank(message = "路由ID不能为空")
    private String routeId;

    @NotBlank(message = "路由路径不能为空")
    private String path;

    @NotBlank(message = "目标地址不能为空")
    private String targetUri;

    private String method;

    private List<String> methods;

    private Map<String, String> headers;

    private Map<String, String> queryParams;

    private Integer order = 0;

    private Boolean enabled = true;

    private Boolean stripPrefix = true;

    private List<String> filters;

    private Map<String, Object> metadata;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private String createdBy;

    private String updatedBy;
}
