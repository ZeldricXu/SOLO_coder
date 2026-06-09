package com.loganalytics.common.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class AlertRule {
    public enum ConditionType {
        METRIC_THRESHOLD,
        PATTERN_FREQUENCY,
        KEYWORD_MATCH,
        ANOMALY_TYPE,
        ERROR_RATE
    }

    public enum Operator {
        GT, GTE, LT, LTE, EQ, NEQ, CONTAINS, MATCHES
    }

    public enum NotificationChannel {
        EMAIL, WEBHOOK, SLACK, PAGERDUTY, SMS
    }

    private String id;
    private String name;
    private String description;
    private boolean enabled;
    private ConditionType conditionType;
    private String metricName;
    private Operator operator;
    private double threshold;
    private String keyword;
    private AnomalyEvent.AnomalyType anomalyType;
    private Duration evaluationWindow;
    private int minFiringDurationMinutes;
    private Duration cooldownPeriod;
    private AnomalyEvent.Severity severity;
    private List<String> serviceFilter;
    private List<LogLevel> levelFilter;
    private Map<String, String> labelFilter;
    private List<NotificationChannel> notificationChannels;
    private List<String> notificationTargets;
    private Map<String, String> webhookHeaders;
    private Duration escalationDelay;
    private int maxEscalationLevel;
    private String createdBy;
    private long createdAt;
    private long updatedAt;

    public AlertRule() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public ConditionType getConditionType() { return conditionType; }
    public void setConditionType(ConditionType conditionType) { this.conditionType = conditionType; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public Operator getOperator() { return operator; }
    public void setOperator(Operator operator) { this.operator = operator; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public AnomalyEvent.AnomalyType getAnomalyType() { return anomalyType; }
    public void setAnomalyType(AnomalyEvent.AnomalyType anomalyType) { this.anomalyType = anomalyType; }

    public Duration getEvaluationWindow() { return evaluationWindow; }
    public void setEvaluationWindow(Duration evaluationWindow) { this.evaluationWindow = evaluationWindow; }

    public int getMinFiringDurationMinutes() { return minFiringDurationMinutes; }
    public void setMinFiringDurationMinutes(int minFiringDurationMinutes) { this.minFiringDurationMinutes = minFiringDurationMinutes; }

    public Duration getCooldownPeriod() { return cooldownPeriod; }
    public void setCooldownPeriod(Duration cooldownPeriod) { this.cooldownPeriod = cooldownPeriod; }

    public AnomalyEvent.Severity getSeverity() { return severity; }
    public void setSeverity(AnomalyEvent.Severity severity) { this.severity = severity; }

    public List<String> getServiceFilter() { return serviceFilter; }
    public void setServiceFilter(List<String> serviceFilter) { this.serviceFilter = serviceFilter; }

    public List<LogLevel> getLevelFilter() { return levelFilter; }
    public void setLevelFilter(List<LogLevel> levelFilter) { this.levelFilter = levelFilter; }

    public Map<String, String> getLabelFilter() { return labelFilter; }
    public void setLabelFilter(Map<String, String> labelFilter) { this.labelFilter = labelFilter; }

    public List<NotificationChannel> getNotificationChannels() { return notificationChannels; }
    public void setNotificationChannels(List<NotificationChannel> notificationChannels) { this.notificationChannels = notificationChannels; }

    public List<String> getNotificationTargets() { return notificationTargets; }
    public void setNotificationTargets(List<String> notificationTargets) { this.notificationTargets = notificationTargets; }

    public Map<String, String> getWebhookHeaders() { return webhookHeaders; }
    public void setWebhookHeaders(Map<String, String> webhookHeaders) { this.webhookHeaders = webhookHeaders; }

    public Duration getEscalationDelay() { return escalationDelay; }
    public void setEscalationDelay(Duration escalationDelay) { this.escalationDelay = escalationDelay; }

    public int getMaxEscalationLevel() { return maxEscalationLevel; }
    public void setMaxEscalationLevel(int maxEscalationLevel) { this.maxEscalationLevel = maxEscalationLevel; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
