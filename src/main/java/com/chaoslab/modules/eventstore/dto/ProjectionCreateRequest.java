package com.chaoslab.modules.eventstore.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class ProjectionCreateRequest {

    @NotBlank(message = "投影名称不能为空")
    private String name;

    @NotBlank(message = "聚合类型不能为空")
    private String aggregateType;

    private Map<String, Object> handlerConfig;
}
