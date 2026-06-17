package com.enterprise.risk.common.rule;

import com.enterprise.risk.common.alert.AlertSeverity;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleDefinition implements Serializable {

    @JsonProperty("rule_id")
    private String ruleId;

    @JsonProperty("rule_name")
    private String ruleName;

    @JsonProperty("rule_type")
    private RuleType ruleType;

    @JsonProperty("business_line")
    private String businessLine;

    @JsonProperty("event_types")
    private List<String> eventTypes;

    @JsonProperty("priority")
    @Builder.Default
    private Integer priority = 100;

    @JsonProperty("short_circuit")
    @Builder.Default
    private Boolean shortCircuit = false;

    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("severity")
    @Builder.Default
    private AlertSeverity severity = AlertSeverity.MEDIUM;

    @JsonProperty("dsl_expression")
    private String dslExpression;

    @JsonProperty("window_config")
    private WindowConfig windowConfig;

    @JsonProperty("sequence_config")
    private SequenceConfig sequenceConfig;

    @JsonProperty("model_weight")
    @Builder.Default
    private Double modelWeight = 0.5;

    @JsonProperty("threshold")
    @Builder.Default
    private Double threshold = 0.7;

    @JsonProperty("escalation_threshold")
    private Integer escalationThreshold;

    @JsonProperty("suppression_rules")
    private List<String> suppressionRuleIds;

    @JsonProperty("actions")
    private List<String> actionIds;

    @JsonProperty("description")
    private String description;

    @JsonProperty("created_at")
    @Builder.Default
    private Long createdAt = Instant.now().toEpochMilli();

    @JsonProperty("updated_at")
    @Builder.Default
    private Long updatedAt = Instant.now().toEpochMilli();

    @JsonProperty("version")
    @Builder.Default
    private Integer version = 1;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WindowConfig implements Serializable {
        @JsonProperty("window_size_ms")
        private Long windowSizeMs;

        @JsonProperty("aggregation_field")
        private String aggregationField;

        @JsonProperty("aggregation_type")
        private AggregationType aggregationType;

        @JsonProperty("group_by")
        private List<String> groupBy;

        @JsonProperty("threshold_value")
        private Double thresholdValue;

        @JsonProperty("operator")
        private String operator;

        public enum AggregationType {
            SUM, AVG, COUNT, MAX, MIN, DISTINCT_COUNT
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SequenceConfig implements Serializable {
        @JsonProperty("pattern")
        private String pattern;

        @JsonProperty("time_window_ms")
        private Long timeWindowMs;

        @JsonProperty("event_mappings")
        private List<EventMapping> eventMappings;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class EventMapping implements Serializable {
            @JsonProperty("step_name")
            private String stepName;
            @JsonProperty("event_type")
            private String eventType;
            @JsonProperty("condition")
            private String condition;
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean shouldShortCircuit() {
        return Boolean.TRUE.equals(shortCircuit);
    }
}
