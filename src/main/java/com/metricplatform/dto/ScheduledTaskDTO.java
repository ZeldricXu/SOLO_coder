package com.metricplatform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class ScheduledTaskDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    @NotBlank(message = "任务类型不能为空")
    private String taskType;

    private String cronExpression;

    private List<String> dependencies;

    private Map<String, Object> parameters;

    private Integer retryCount = 0;

    private Long timeout = 3600000L;
}
