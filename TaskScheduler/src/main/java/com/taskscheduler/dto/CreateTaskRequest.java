package com.taskscheduler.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateTaskRequest {

    private String taskId;
    private String taskName;
    private String taskType;
    private String executeCommand;
    private ScheduleConfig scheduleConfig;
    private Integer retryCount = 0;
    private Integer timeout = 300;
    private Integer priority = 1;
    private List<String> dependencies;
    private Boolean enabled = true;
    private Integer maxConcurrent = 1;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleConfig {
        private String cron;
        private String timezone = "Asia/Shanghai";
    }
}
