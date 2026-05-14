package com.configcenter.push.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "config-center.push")
public class PushProperties {
    
    private Integer maxRetryCount = 3;
    private Integer retryIntervalSeconds = 60;
    private Integer pushTimeoutSeconds = 30;
    private Integer parallelPushCount = 10;
    private String pushEndpoint = "/api/v1/configs/refresh";
    private Boolean enabled = true;
    
    private AsyncPushProperties async = new AsyncPushProperties();
    
    private Map<String, Integer> groupParallelism = new HashMap<>();
    
    private List<GroupParallelismConfig> groupConfigs = new ArrayList<>();
    
    private Integer minParallelCount = 1;
    private Integer maxParallelCount = 100;
    
    private ParallelCalculationStrategy calculationStrategy = ParallelCalculationStrategy.INSTANCE_COUNT_BASED;
    
    public Integer getParallelismForGroup(String groupId) {
        return getParallelismForGroup(groupId, 0);
    }
    
    public Integer getParallelismForGroup(String groupId, int instanceCount) {
        if (groupParallelism.containsKey(groupId)) {
            return normalizeParallelCount(groupParallelism.get(groupId));
        }
        
        if (groupConfigs != null) {
            for (GroupParallelismConfig config : groupConfigs) {
                if (config.getGroupId() != null && config.getGroupId().equals(groupId)) {
                    return calculateParallelism(config, instanceCount);
                }
            }
        }
        
        int result;
        switch (calculationStrategy) {
            case FIXED:
                result = parallelPushCount;
                break;
            case INSTANCE_COUNT_BASED:
                if (instanceCount <= 0) {
                    return parallelPushCount;
                }
                result = calculateByInstanceCount(instanceCount);
                break;
            case PERCENTAGE:
                if (instanceCount <= 0) {
                    return parallelPushCount;
                }
                result = (int) Math.ceil(instanceCount * 0.2);
                break;
            default:
                result = parallelPushCount;
        }
        
        return normalizeParallelCount(result);
    }
    
    private Integer calculateParallelism(GroupParallelismConfig config, int instanceCount) {
        if (config.getParallelism() != null && config.getParallelism() > 0) {
            return normalizeParallelCount(config.getParallelism());
        }
        
        switch (config.getStrategy() != null ? config.getStrategy() : ParallelCalculationStrategy.INSTANCE_COUNT_BASED) {
            case FIXED:
                return normalizeParallelCount(config.getParallelism() != null ? config.getParallelism() : parallelPushCount);
            case INSTANCE_COUNT_BASED:
                return calculateByInstanceCount(instanceCount);
            case PERCENTAGE:
                int percentage = config.getParallelismPercentage() != null ? config.getParallelismPercentage() : 20;
                return (int) Math.ceil(instanceCount * percentage / 100.0);
            default:
                return parallelPushCount;
        }
    }
    
    private int calculateByInstanceCount(int instanceCount) {
        if (instanceCount <= 5) {
            return 2;
        } else if (instanceCount <= 20) {
            return 5;
        } else if (instanceCount <= 50) {
            return 10;
        } else if (instanceCount <= 100) {
            return 20;
        } else if (instanceCount <= 200) {
            return 30;
        } else {
            return 50;
        }
    }
    
    private int normalizeParallelCount(int count) {
        int normalized = count;
        if (normalized < minParallelCount) {
            normalized = minParallelCount;
        }
        if (normalized > maxParallelCount) {
            normalized = maxParallelCount;
        }
        return normalized;
    }
    
    public enum ParallelCalculationStrategy {
        FIXED,
        INSTANCE_COUNT_BASED,
        PERCENTAGE
    }
    
    @Data
    public static class AsyncPushProperties {
        private Boolean enabled = true;
        private Integer queueCapacity = 1000;
        private Integer corePoolSize = 5;
        private Integer maxPoolSize = 20;
        private Integer keepAliveSeconds = 60;
        private Integer retryCount = 3;
        private Integer retryIntervalMillis = 5000;
    }
    
    @Data
    public static class GroupParallelismConfig {
        private String groupId;
        private String groupType;
        private Integer parallelism;
        private Integer parallelismPercentage;
        private ParallelCalculationStrategy strategy = ParallelCalculationStrategy.INSTANCE_COUNT_BASED;
        private Integer minInstanceThreshold = 10;
        private String description;
    }
}