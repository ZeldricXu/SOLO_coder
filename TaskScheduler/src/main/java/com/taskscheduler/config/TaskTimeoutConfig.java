package com.taskscheduler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "taskscheduler.timeout")
public class TaskTimeoutConfig {

    private int defaultTimeout = 300;
    private Map<String, TaskTypeTimeout> taskTypes = new HashMap<>();

    @Data
    public static class TaskTypeTimeout {
        private int timeoutSeconds;
        private String description;
    }

    public int getTimeoutForTaskType(String taskType) {
        if (taskType != null && taskTypes.containsKey(taskType)) {
            TaskTypeTimeout config = taskTypes.get(taskType);
            if (config != null && config.getTimeoutSeconds() > 0) {
                return config.getTimeoutSeconds();
            }
        }
        return defaultTimeout;
    }

    public boolean hasCustomTimeout(String taskType) {
        return taskType != null && taskTypes.containsKey(taskType);
    }
}
