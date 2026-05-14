package com.servicedesk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "servicedesk")
public class ServiceDeskProperties {

    private LoadWarningConfig loadWarning = new LoadWarningConfig();
    private ResponseTimeoutConfig responseTimeout = new ResponseTimeoutConfig();
    private PriorityAsyncConfig priorityAsync = new PriorityAsyncConfig();
    private AssignmentStrategyConfig assignmentStrategy = new AssignmentStrategyConfig();

    @Data
    public static class LoadWarningConfig {
        private boolean enabled = true;
        private int smallTeamThreshold = 10;
        private int largeTeamThreshold = 50;
        private double smallTeamWarningThreshold = 0.6;
        private double mediumTeamWarningThreshold = 0.75;
        private double largeTeamWarningThreshold = 0.85;
        private Map<String, Double> groupOverrideThresholds = new HashMap<>();
        private List<TeamConfig> teamConfigs = new ArrayList<>();

        public double getThresholdByTeamSize(int teamSize) {
            for (TeamConfig config : teamConfigs) {
                if (teamSize >= config.getMinSize() && (config.getMaxSize() == null || teamSize <= config.getMaxSize())) {
                    return config.getWarningThreshold();
                }
            }
            if (teamSize < smallTeamThreshold) {
                return smallTeamWarningThreshold;
            } else if (teamSize >= largeTeamThreshold) {
                return largeTeamWarningThreshold;
            } else {
                return mediumTeamWarningThreshold;
            }
        }

        public double getThresholdByGroup(String group, int teamSize) {
            if (groupOverrideThresholds.containsKey(group)) {
                return groupOverrideThresholds.get(group);
            }
            return getThresholdByTeamSize(teamSize);
        }

        @Data
        public static class TeamConfig {
            private String name;
            private Integer minSize = 0;
            private Integer maxSize;
            private double warningThreshold;
            private String strategy;
        }
    }

    @Data
    public static class ResponseTimeoutConfig {
        private boolean enabled = true;
        private int defaultTimeoutSeconds = 600;
        private double warningRatio = 0.7;
        private Map<String, UrgencyConfig> urgencyConfigs = new HashMap<>();

        public ResponseTimeoutConfig() {
            urgencyConfigs.put("high", new UrgencyConfig("high", 300, "紧急工单响应超时"));
            urgencyConfigs.put("medium", new UrgencyConfig("medium", 600, "普通工单响应超时"));
            urgencyConfigs.put("low", new UrgencyConfig("low", 1800, "低优先级工单响应超时"));
        }

        public int getTimeoutByUrgency(String urgency) {
            if (urgency == null) {
                return defaultTimeoutSeconds;
            }
            String normalizedUrgency = urgency.toLowerCase();
            if (urgencyConfigs.containsKey(normalizedUrgency)) {
                return urgencyConfigs.get(normalizedUrgency).getTimeoutSeconds();
            }
            return defaultTimeoutSeconds;
        }

        public int getWarningThresholdByUrgency(String urgency) {
            int timeout = getTimeoutByUrgency(urgency);
            return (int) (timeout * warningRatio);
        }

        public UrgencyConfig getUrgencyConfig(String urgency) {
            if (urgency == null) {
                return null;
            }
            return urgencyConfigs.get(urgency.toLowerCase());
        }

        public void addUrgencyConfig(String urgency, int timeoutSeconds, String alertMessage) {
            urgencyConfigs.put(urgency.toLowerCase(), new UrgencyConfig(urgency, timeoutSeconds, alertMessage));
        }

        @Data
        public static class UrgencyConfig {
            private String urgencyLevel;
            private int timeoutSeconds;
            private String alertMessage;

            public UrgencyConfig() {}

            public UrgencyConfig(String urgencyLevel, int timeoutSeconds, String alertMessage) {
                this.urgencyLevel = urgencyLevel;
                this.timeoutSeconds = timeoutSeconds;
                this.alertMessage = alertMessage;
            }
        }
    }

    @Data
    public static class PriorityAsyncConfig {
        private boolean redisEnabled = true;
        private String redisQueueKey = "servicedesk:priority:queue";
        private String redisProcessingKey = "servicedesk:priority:processing";
        private String redisFailedKey = "servicedesk:priority:failed";
        private int maxRetries = 3;
        private long retryIntervalMs = 1000;
        private int threadPoolSize = 4;
        private int pollIntervalMs = 500;
        private long taskExpirationSeconds = 3600;
        private int batchSize = 10;
    }

    @Data
    public static class AssignmentStrategyConfig {
        private String defaultStrategy = "load_balanced";
        private Map<String, StrategyConfig> strategies = new HashMap<>();
        private Map<String, String> groupStrategyMapping = new HashMap<>();
        private Map<String, String> categoryStrategyMapping = new HashMap<>();

        public AssignmentStrategyConfig() {
            strategies.put("load_balanced", new StrategyConfig(
                    "load_balanced",
                    "负载均衡",
                    "选择剩余容量最大的客服",
                    true
            ));
            strategies.put("skill_match", new StrategyConfig(
                    "skill_match",
                    "技能匹配",
                    "选择技能最匹配的客服",
                    true
            ));
            strategies.put("round_robin", new StrategyConfig(
                    "round_robin",
                    "轮询分配",
                    "按顺序轮流分配",
                    true
            ));
            strategies.put("least_busy", new StrategyConfig(
                    "least_busy",
                    "最空闲优先",
                    "选择当前工单最少的客服",
                    true
            ));
            strategies.put("fastest_response", new StrategyConfig(
                    "fastest_response",
                    "响应最快优先",
                    "选择平均响应时间最短的客服",
                    true
            ));
        }

        public String getStrategyForTicket(String category, String group) {
            if (category != null && categoryStrategyMapping.containsKey(category)) {
                return categoryStrategyMapping.get(category);
            }
            if (group != null && groupStrategyMapping.containsKey(group)) {
                return groupStrategyMapping.get(group);
            }
            return defaultStrategy;
        }

        public StrategyConfig getStrategyConfig(String strategyName) {
            return strategies.get(strategyName);
        }

        public boolean isStrategyEnabled(String strategyName) {
            StrategyConfig config = strategies.get(strategyName);
            return config != null && config.isEnabled();
        }

        @Data
        public static class StrategyConfig {
            private String name;
            private String displayName;
            private String description;
            private boolean enabled;
            private Map<String, Object> parameters = new HashMap<>();

            public StrategyConfig() {}

            public StrategyConfig(String name, String displayName, String description, boolean enabled) {
                this.name = name;
                this.displayName = displayName;
                this.description = description;
                this.enabled = enabled;
            }
        }
    }
}
