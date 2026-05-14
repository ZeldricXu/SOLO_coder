package com.schedulebook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "schedulebook.dispatch")
public class DispatchStrategyConfig {

    private String defaultStrategy = "priority";
    private Map<String, StrategyConfig> strategies = new HashMap<>();

    public DispatchStrategyConfig() {
        StrategyConfig priorityStrategy = new StrategyConfig();
        priorityStrategy.setType("priority");
        priorityStrategy.setDescription("按资源优先级调度");
        priorityStrategy.setEnabled(true);
        priorityStrategy.setSortBy("priority");
        priorityStrategy.setSortOrder("desc");
        priorityStrategy.setSortFields(new ArrayList<>(List.of("priority", "currentOccupancy")));
        strategies.put("priority", priorityStrategy);

        StrategyConfig loadBalanceStrategy = new StrategyConfig();
        loadBalanceStrategy.setType("load_balance");
        loadBalanceStrategy.setDescription("负载均衡调度");
        loadBalanceStrategy.setEnabled(true);
        loadBalanceStrategy.setSortBy("currentOccupancy");
        loadBalanceStrategy.setSortOrder("asc");
        loadBalanceStrategy.setSortFields(new ArrayList<>(List.of("currentOccupancy", "priority")));
        strategies.put("load_balance", loadBalanceStrategy);

        StrategyConfig frequencyStrategy = new StrategyConfig();
        frequencyStrategy.setType("frequency");
        frequencyStrategy.setDescription("使用频率均衡调度");
        frequencyStrategy.setEnabled(true);
        frequencyStrategy.setSortBy("usageCount");
        frequencyStrategy.setSortOrder("asc");
        frequencyStrategy.setSortFields(new ArrayList<>(List.of("usageCount", "priority")));
        strategies.put("frequency", frequencyStrategy);

        StrategyConfig randomStrategy = new StrategyConfig();
        randomStrategy.setType("random");
        randomStrategy.setDescription("随机调度");
        randomStrategy.setEnabled(false);
        strategies.put("random", randomStrategy);
    }

    public String getDefaultStrategy() {
        return defaultStrategy;
    }

    public void setDefaultStrategy(String defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
    }

    public Map<String, StrategyConfig> getStrategies() {
        return strategies;
    }

    public void setStrategies(Map<String, StrategyConfig> strategies) {
        this.strategies = strategies;
    }

    public StrategyConfig getStrategyConfig(String strategyName) {
        return strategies.get(strategyName);
    }

    public boolean isStrategyEnabled(String strategyName) {
        StrategyConfig config = strategies.get(strategyName);
        return config != null && config.isEnabled();
    }

    public void addStrategy(String name, StrategyConfig config) {
        strategies.put(name, config);
    }

    public void removeStrategy(String name) {
        strategies.remove(name);
    }

    public void updateStrategy(String name, StrategyConfig config) {
        if (strategies.containsKey(name)) {
            strategies.put(name, config);
        }
    }

    public static class StrategyConfig {
        private String type;
        private String description;
        private boolean enabled = true;
        private String sortBy;
        private String sortOrder = "asc";
        private List<String> sortFields = new ArrayList<>();
        private Map<String, Object> parameters = new HashMap<>();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSortBy() {
            return sortBy;
        }

        public void setSortBy(String sortBy) {
            this.sortBy = sortBy;
        }

        public String getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(String sortOrder) {
            this.sortOrder = sortOrder;
        }

        public List<String> getSortFields() {
            return sortFields;
        }

        public void setSortFields(List<String> sortFields) {
            this.sortFields = sortFields;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }
    }
}
