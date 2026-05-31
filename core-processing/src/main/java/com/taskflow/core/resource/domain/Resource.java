package com.taskflow.core.resource.domain;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 资源 - 领域模型
 */
@Data
@Builder
public class Resource {
    private String resourceId;
    private String tenantId;
    private String type;
    private String name;
    private String description;
    private String status;
    private Map<String, Object> attributes;
    private Map<String, String> labels;
    private String configId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
