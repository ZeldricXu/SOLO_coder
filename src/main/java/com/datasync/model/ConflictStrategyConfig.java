package com.datasync.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConflictStrategyConfig {

    @JsonProperty("config_id")
    private String configId;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("default_strategy")
    private String defaultStrategy;

    @JsonProperty("strategy_mappings")
    private Map<String, ConflictStrategyMapping> strategyMappings;

    @JsonProperty("priority_rules")
    private List<PriorityOverrideRule> priorityRules;

    @JsonProperty("custom_conditions")
    private List<CustomStrategyCondition> customConditions;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    public ConflictStrategyConfig() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.enabled = true;
        this.strategyMappings = new LinkedHashMap<>();
        this.priorityRules = new ArrayList<>();
        this.customConditions = new ArrayList<>();
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultStrategy() {
        return defaultStrategy;
    }

    public void setDefaultStrategy(String defaultStrategy) {
        this.defaultStrategy = defaultStrategy;
    }

    public Map<String, ConflictStrategyMapping> getStrategyMappings() {
        return strategyMappings;
    }

    public void setStrategyMappings(Map<String, ConflictStrategyMapping> strategyMappings) {
        this.strategyMappings = strategyMappings;
    }

    public List<PriorityOverrideRule> getPriorityRules() {
        return priorityRules;
    }

    public void setPriorityRules(List<PriorityOverrideRule> priorityRules) {
        this.priorityRules = priorityRules;
    }

    public List<CustomStrategyCondition> getCustomConditions() {
        return customConditions;
    }

    public void setCustomConditions(List<CustomStrategyCondition> customConditions) {
        this.customConditions = customConditions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void addMapping(String conflictType, String strategy, Integer minPriority, Integer maxPriority) {
        ConflictStrategyMapping mapping = new ConflictStrategyMapping();
        mapping.setConflictType(conflictType);
        mapping.setStrategy(strategy);
        mapping.setMinPriority(minPriority);
        mapping.setMaxPriority(maxPriority);
        mapping.setEnabled(true);
        this.strategyMappings.put(conflictType, mapping);
    }

    public ConflictStrategyMapping getMapping(String conflictType) {
        return this.strategyMappings.get(conflictType);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConflictStrategyMapping {

        @JsonProperty("conflict_type")
        private String conflictType;

        @JsonProperty("strategy")
        private String strategy;

        @JsonProperty("min_priority")
        private Integer minPriority;

        @JsonProperty("max_priority")
        private Integer maxPriority;

        @JsonProperty("enabled")
        private Boolean enabled;

        @JsonProperty("notes")
        private String notes;

        public ConflictStrategyMapping() {
            this.enabled = true;
        }

        public String getConflictType() {
            return conflictType;
        }

        public void setConflictType(String conflictType) {
            this.conflictType = conflictType;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public Integer getMinPriority() {
            return minPriority;
        }

        public void setMinPriority(Integer minPriority) {
            this.minPriority = minPriority;
        }

        public Integer getMaxPriority() {
            return maxPriority;
        }

        public void setMaxPriority(Integer maxPriority) {
            this.maxPriority = maxPriority;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }

        public boolean matches(Integer priority) {
            if (!Boolean.TRUE.equals(enabled)) {
                return false;
            }
            if (priority == null) {
                return true;
            }
            boolean minOk = minPriority == null || priority >= minPriority;
            boolean maxOk = maxPriority == null || priority <= maxPriority;
            return minOk && maxOk;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PriorityOverrideRule {

        @JsonProperty("rule_id")
        private String ruleId;

        @JsonProperty("name")
        private String name;

        @JsonProperty("conflict_type")
        private String conflictType;

        @JsonProperty("field_pattern")
        private String fieldPattern;

        @JsonProperty("override_priority")
        private Integer overridePriority;

        @JsonProperty("enabled")
        private Boolean enabled;

        public PriorityOverrideRule() {
            this.enabled = true;
        }

        public String getRuleId() {
            return ruleId;
        }

        public void setRuleId(String ruleId) {
            this.ruleId = ruleId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getConflictType() {
            return conflictType;
        }

        public void setConflictType(String conflictType) {
            this.conflictType = conflictType;
        }

        public String getFieldPattern() {
            return fieldPattern;
        }

        public void setFieldPattern(String fieldPattern) {
            this.fieldPattern = fieldPattern;
        }

        public Integer getOverridePriority() {
            return overridePriority;
        }

        public void setOverridePriority(Integer overridePriority) {
            this.overridePriority = overridePriority;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CustomStrategyCondition {

        @JsonProperty("condition_id")
        private String conditionId;

        @JsonProperty("name")
        private String name;

        @JsonProperty("expression")
        private String expression;

        @JsonProperty("strategy")
        private String strategy;

        @JsonProperty("order")
        private Integer order;

        @JsonProperty("enabled")
        private Boolean enabled;

        @JsonProperty("description")
        private String description;

        public CustomStrategyCondition() {
            this.enabled = true;
            this.order = 0;
        }

        public String getConditionId() {
            return conditionId;
        }

        public void setConditionId(String conditionId) {
            this.conditionId = conditionId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getExpression() {
            return expression;
        }

        public void setExpression(String expression) {
            this.expression = expression;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public Integer getOrder() {
            return order;
        }

        public void setOrder(Integer order) {
            this.order = order;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConflictStrategyConfig that = (ConflictStrategyConfig) o;
        return Objects.equals(configId, that.configId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(configId);
    }
}
