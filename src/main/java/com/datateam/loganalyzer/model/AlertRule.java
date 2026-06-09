package com.datateam.loganalyzer.model;

import java.util.ArrayList;
import java.util.List;

public class AlertRule {
    public enum RuleType {
        THRESHOLD,
        COMPOSITE
    }

    public enum Operator {
        AND,
        OR
    }

    public enum Comparison {
        GT,
        LT,
        GTE,
        LTE,
        EQ,
        NEQ
    }

    private String id;
    private String name;
    private String description;
    private RuleType type;
    private AlertSeverity severity;
    private boolean enabled;

    private String metric;
    private Comparison comparison;
    private double threshold;
    private int minViolations;
    private int cooldownSeconds;
    private int escalationMinutes;

    private Operator operator;
    private List<AlertRule> children;

    private String detectorClassName;
    private List<String> notificationChannels;

    public AlertRule() {
        this.enabled = true;
        this.minViolations = 1;
        this.cooldownSeconds = 300;
        this.escalationMinutes = 10;
        this.children = new ArrayList<>();
        this.notificationChannels = new ArrayList<>();
    }

    public boolean evaluate(double value) {
        if (type != RuleType.THRESHOLD) {
            throw new IllegalStateException("Cannot evaluate composite rule directly");
        }
        switch (comparison) {
            case GT: return value > threshold;
            case LT: return value < threshold;
            case GTE: return value >= threshold;
            case LTE: return value <= threshold;
            case EQ: return value == threshold;
            case NEQ: return value != threshold;
            default: return false;
        }
    }

    public boolean evaluateComposite(List<Boolean> childResults) {
        if (type != RuleType.COMPOSITE) {
            throw new IllegalStateException("Cannot evaluate threshold rule as composite");
        }
        if (operator == Operator.AND) {
            return childResults.stream().allMatch(Boolean::booleanValue);
        } else {
            return childResults.stream().anyMatch(Boolean::booleanValue);
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public RuleType getType() {
        return type;
    }

    public void setType(RuleType type) {
        this.type = type;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(AlertSeverity severity) {
        this.severity = severity;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public Comparison getComparison() {
        return comparison;
    }

    public void setComparison(Comparison comparison) {
        this.comparison = comparison;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public int getMinViolations() {
        return minViolations;
    }

    public void setMinViolations(int minViolations) {
        this.minViolations = minViolations;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public int getEscalationMinutes() {
        return escalationMinutes;
    }

    public void setEscalationMinutes(int escalationMinutes) {
        this.escalationMinutes = escalationMinutes;
    }

    public Operator getOperator() {
        return operator;
    }

    public void setOperator(Operator operator) {
        this.operator = operator;
    }

    public List<AlertRule> getChildren() {
        return children;
    }

    public void setChildren(List<AlertRule> children) {
        this.children = children;
    }

    public void addChild(AlertRule child) {
        this.children.add(child);
    }

    public List<String> getNotificationChannels() {
        return notificationChannels;
    }

    public void setNotificationChannels(List<String> notificationChannels) {
        this.notificationChannels = notificationChannels;
    }

    public void addNotificationChannel(String channel) {
        this.notificationChannels.add(channel);
    }

    public String getDetectorClassName() {
        return detectorClassName;
    }

    public void setDetectorClassName(String detectorClassName) {
        this.detectorClassName = detectorClassName;
    }

    public boolean hasDetectorConfigured() {
        return detectorClassName != null && !detectorClassName.trim().isEmpty();
    }
}
