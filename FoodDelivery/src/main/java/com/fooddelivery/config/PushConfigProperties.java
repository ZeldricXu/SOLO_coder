package com.fooddelivery.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
@Configuration
@ConfigurationProperties(prefix = "fooddelivery.push")
public class PushConfigProperties {

    private Map<String, PushStrategyConfig> strategies = new HashMap<>();

    private int batchThreshold = 10;

    private String defaultStrategy = "batch";

    @Data
    public static class PushStrategyConfig {
        private String strategy;
        private String description;
        private boolean important;
    }

    public PushStrategyConfig getStrategyConfig(String statusType) {
        PushStrategyConfig config = strategies.get(statusType);
        if (config == null) {
            config = createDefaultConfig();
        }
        return config;
    }

    private PushStrategyConfig createDefaultConfig() {
        PushStrategyConfig config = new PushStrategyConfig();
        config.setStrategy(defaultStrategy);
        config.setImportant(false);
        config.setDescription("默认策略");
        return config;
    }

    public boolean isImportantStatus(String statusType) {
        PushStrategyConfig config = strategies.get(statusType);
        return config != null && config.isImportant();
    }

    public String getPushStrategy(String statusType) {
        PushStrategyConfig config = getStrategyConfig(statusType);
        return config.getStrategy();
    }

    public Set<String> getImportantStatuses() {
        Set<String> importantStatuses = new HashSet<>();
        for (Map.Entry<String, PushStrategyConfig> entry : strategies.entrySet()) {
            if (entry.getValue().isImportant()) {
                importantStatuses.add(entry.getKey());
            }
        }
        return importantStatuses;
    }
}
