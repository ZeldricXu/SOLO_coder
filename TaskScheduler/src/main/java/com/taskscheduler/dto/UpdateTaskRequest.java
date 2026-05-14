package com.taskscheduler.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTaskRequest {

    private String taskName;
    private String taskType;
    private String executeCommand;
    private CreateTaskRequest.ScheduleConfig scheduleConfig;
    private Integer retryCount;
    private Integer timeout;
    private Integer priority;
    private List<String> dependencies;
    private Boolean enabled;
    private Integer maxConcurrent;
}
