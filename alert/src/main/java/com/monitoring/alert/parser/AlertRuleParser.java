package com.monitoring.alert.parser;

import com.monitoring.alert.model.AlertRule;
import com.monitoring.common.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class AlertRuleParser {

    private static final Set<String> VALID_OPERATORS = Set.of(
            ">", "<", ">=", "<=", "==", "!=",
            "gt", "lt", "gte", "lte", "eq", "neq"
    );

    private static final Set<String> VALID_SEVERITIES = Set.of("critical", "warning", "info");

    public AlertRule parse(Map<String, Object> ruleData) {
        validate(ruleData);

        return AlertRule.builder()
                .ruleId((String) ruleData.get("ruleId"))
                .name((String) ruleData.get("name"))
                .description((String) ruleData.getOrDefault("description", ""))
                .namespace((String) ruleData.getOrDefault("namespace", "default"))
                .metricName((String) ruleData.get("metricName"))
                .operator(normalizeOperator((String) ruleData.get("operator")))
                .threshold(((Number) ruleData.get("threshold")).doubleValue())
                .durationSeconds((Integer) ruleData.getOrDefault("durationSeconds", 60))
                .severity(((String) ruleData.getOrDefault("severity", "warning")).toLowerCase())
                .notificationChannels((List<String>) ruleData.getOrDefault("notificationChannels", Collections.emptyList()))
                .labels((Map<String, String>) ruleData.getOrDefault("labels", Collections.emptyMap()))
                .annotations((Map<String, String>) ruleData.getOrDefault("annotations", Collections.emptyMap()))
                .enabled((Boolean) ruleData.getOrDefault("enabled", true))
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
    }

    public void validate(Map<String, Object> ruleData) {
        Map<String, String> errors = new HashMap<>();

        if (ruleData.get("name") == null) {
            errors.put("name", "Name is required");
        }

        if (ruleData.get("metricName") == null) {
            errors.put("metricName", "Metric name is required");
        }

        String operator = (String) ruleData.get("operator");
        if (operator == null) {
            errors.put("operator", "Operator is required");
        } else if (!VALID_OPERATORS.contains(operator.toLowerCase())) {
            errors.put("operator", "Invalid operator: " + operator);
        }

        if (ruleData.get("threshold") == null) {
            errors.put("threshold", "Threshold is required");
        } else if (!(ruleData.get("threshold") instanceof Number)) {
            errors.put("threshold", "Threshold must be a number");
        }

        String severity = (String) ruleData.get("severity");
        if (severity != null && !VALID_SEVERITIES.contains(severity.toLowerCase())) {
            errors.put("severity", "Invalid severity, must be one of: critical, warning, info");
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Alert rule validation failed", errors);
        }
    }

    private String normalizeOperator(String operator) {
        return switch (operator.toLowerCase()) {
            case "gt" -> ">";
            case "lt" -> "<";
            case "gte" -> ">=";
            case "lte" -> "<=";
            case "eq" -> "==";
            case "neq" -> "!=";
            default -> operator;
        };
    }

    public boolean evaluate(double currentValue, String operator, double threshold) {
        return switch (operator) {
            case ">" -> currentValue > threshold;
            case "<" -> currentValue < threshold;
            case ">=" -> currentValue >= threshold;
            case "<=" -> currentValue <= threshold;
            case "==" -> Math.abs(currentValue - threshold) < 0.0001;
            case "!=" -> Math.abs(currentValue - threshold) >= 0.0001;
            default -> false;
        };
    }
}
