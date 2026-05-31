package com.device.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class InferenceTaskResultRequest {
    @NotBlank(message = "taskId不能为空")
    private String taskId;

    private Map<String, Object> outputData;
    private Double confidence;
    private Long inferenceTimeMs;
    private String errorDetail;
}
