package com.device.platform.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.Map;

@Data
public class ProcessRequest {
    @NotBlank(message = "traceId不能为空")
    @Size(min = 1, max = 128, message = "traceId长度必须在1-128字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "traceId格式不正确")
    private String traceId;

    @NotBlank(message = "namespace不能为空")
    @Size(min = 1, max = 64, message = "namespace长度必须在1-64字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "namespace格式不正确")
    private String namespace;

    @NotNull(message = "payload不能为空")
    @Size(max = 1000, message = "payload最多包含1000个键值对")
    private Map<String, Object> payload;

    @Size(max = 100, message = "params最多包含100个键值对")
    private Map<String, Object> params;

    @Size(max = 50, message = "labels最多包含50个键值对")
    private Map<@Size(max = 64) String, @Size(max = 256) String> labels;

    @Size(max = 64, message = "entityType长度不能超过64字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "entityType格式不正确")
    private String entityType;

    @Size(max = 128, message = "entityId长度不能超过128字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "entityId格式不正确")
    private String entityId;
}
