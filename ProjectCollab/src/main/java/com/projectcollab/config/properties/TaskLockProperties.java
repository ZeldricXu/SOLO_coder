package com.projectcollab.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "task.lock")
public class TaskLockProperties {

    private Map<String, Integer> priorityTimeout = new HashMap<>();

    public TaskLockProperties() {
        priorityTimeout.put("critical", 300);
        priorityTimeout.put("high", 600);
        priorityTimeout.put("normal", 1200);
        priorityTimeout.put("low", 1800);
    }

    public Map<String, Integer> getPriorityTimeout() {
        return priorityTimeout;
    }

    public void setPriorityTimeout(Map<String, Integer> priorityTimeout) {
        this.priorityTimeout = priorityTimeout;
    }

    public int getTimeout(String priority) {
        if (priority == null) {
            return priorityTimeout.getOrDefault("normal", 1200);
        }
        return priorityTimeout.getOrDefault(priority, priorityTimeout.getOrDefault("normal", 1200));
    }

    public void updateTimeout(String priority, int seconds) {
        priorityTimeout.put(priority, seconds);
    }

    public void removeTimeout(String priority) {
        if (!"normal".equals(priority)) {
            priorityTimeout.remove(priority);
        }
    }
}
