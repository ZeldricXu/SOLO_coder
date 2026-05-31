package com.chaoslab.modules.eventstore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class EventAppendRequest {

    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    @NotBlank(message = "聚合根ID不能为空")
    private String aggregateId;

    @NotBlank(message = "聚合类型不能为空")
    private String aggregateType;

    private Integer eventVersion = 1;

    @NotNull(message = "事件负载不能为空")
    private Map<String, Object> payload;

    private Map<String, Object> metadata;
}
