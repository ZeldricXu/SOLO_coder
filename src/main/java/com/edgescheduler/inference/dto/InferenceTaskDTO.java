package com.edgescheduler.inference.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class InferenceTaskDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String taskId;

    @NotEmpty(message = "modelId cannot be empty")
    private String modelId;

    @NotEmpty(message = "deviceKey cannot be empty")
    private String deviceKey;

    private String taskType;
    private String priority;
    private Map<String, Object> inputData;
    private Map<String, Object> inferenceResult;
    private String status;
    private Double progress;
    private Long inferenceTimeMs;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorDetail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
