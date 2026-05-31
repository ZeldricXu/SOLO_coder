package com.orchestration.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class TaskSubmitRequest {

    @NotBlank(message = "任务编码不能为空")
    private String taskCode;

    private String taskName;

    private String taskType;

    private Map<String, Object> inputData;

    private Map<String, Object> config;

    private String parentInstanceId;
}
