package com.taskscheduler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "taskscheduler.loadbalancer")
public class LoadBalancerConfig {

    private String strategy = "least_load";
    private ResourceWeights resourceWeights = new ResourceWeights();
    private Map<String, String> taskTypeStrategy = new HashMap<>();

    @Data
    public static class ResourceWeights {
        private int taskCount = 40;
        private int cpu = 30;
        private int memory = 30;
    }

    public String getStrategyForTaskType(String taskType) {
        if (taskType != null && taskTypeStrategy.containsKey(taskType)) {
            return taskTypeStrategy.get(taskType);
        }
        return strategy;
    }
}
