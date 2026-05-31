package com.device.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.Map;

@Data
public class InferenceTaskCreateRequest {
    @NotBlank(message = "modelId不能为空")
    private String modelId;

    @NotBlank(message = "deviceId不能为空")
    private String deviceId;

    @NotNull(message = "inputData不能为空")
    private Map<String, Object> inputData;

    private Integer priority;
}
