package com.loganalytics.common.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class Alert {
    public enum AlertStatus {
        PENDING, FIRING, ACKNOWLEDGED, RESOLVED, ESCALATED
    }

    private String id;
    private String ruleId;
    private String ruleName;
    private AlertStatus status;
    private AnomalyEvent.Severity severity;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;
    private String serviceName;
    private String summary;
    private String description;
    private Map<String, Object> labels;
    private List<String> notificationChannels;
    private int escalationLevel;
    private Instant lastNotificationSent;
    private int notificationCount;
    private String acknowledgedBy;
    private Instant acknowledgedAt;

    public Alert() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public AlertStatus getStatus() { return status; }
    public void setStatus(AlertStatus status) { this.status = status; }

    public AnomalyEvent.Severity getSeverity() { return severity; }
    public void setSeverity(AnomalyEvent.Severity severity) { this.severity = severity; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getLabels() { return labels; }
    public void setLabels(Map<String, Object> labels) { this.labels = labels; }

    public List<String> getNotificationChannels() { return notificationChannels; }
    public void setNotificationChannels(List<String> notificationChannels) { this.notificationChannels = notificationChannels; }

    public int getEscalationLevel() { return escalationLevel; }
    public void setEscalationLevel(int escalationLevel) { this.escalationLevel = escalationLevel; }

    public Instant getLastNotificationSent() { return lastNotificationSent; }
    public void setLastNotificationSent(Instant lastNotificationSent) { this.lastNotificationSent = lastNotificationSent; }

    public int getNotificationCount() { return notificationCount; }
    public void setNotificationCount(int notificationCount) { this.notificationCount = notificationCount; }

    public String getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }

    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
}
