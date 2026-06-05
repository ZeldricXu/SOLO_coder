package com.datateam.loganalyzer.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AlertEvent {
    private String ruleId;
    private String ruleName;
    private AlertSeverity severity;
    private String description;
    private Instant triggeredAt;
    private Instant recoveredAt;
    private Instant lastNotifiedAt;
    private int escalationCount;
    private boolean isActive;
    private List<String> details;
    private List<TimeSeriesPoint> affectedPoints;

    public AlertEvent() {
        this.details = new ArrayList<>();
        this.affectedPoints = new ArrayList<>();
        this.triggeredAt = Instant.now();
        this.isActive = true;
        this.escalationCount = 0;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(AlertSeverity severity) {
        this.severity = severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(Instant triggeredAt) {
        this.triggeredAt = triggeredAt;
    }

    public Instant getRecoveredAt() {
        return recoveredAt;
    }

    public void setRecoveredAt(Instant recoveredAt) {
        this.recoveredAt = recoveredAt;
        this.isActive = false;
    }

    public Instant getLastNotifiedAt() {
        return lastNotifiedAt;
    }

    public void setLastNotifiedAt(Instant lastNotifiedAt) {
        this.lastNotifiedAt = lastNotifiedAt;
    }

    public int getEscalationCount() {
        return escalationCount;
    }

    public void setEscalationCount(int escalationCount) {
        this.escalationCount = escalationCount;
    }

    public void incrementEscalation() {
        this.escalationCount++;
        this.severity = this.severity.escalate();
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details;
    }

    public void addDetail(String detail) {
        this.details.add(detail);
    }

    public List<TimeSeriesPoint> getAffectedPoints() {
        return affectedPoints;
    }

    public void setAffectedPoints(List<TimeSeriesPoint> affectedPoints) {
        this.affectedPoints = affectedPoints;
    }

    public void addAffectedPoint(TimeSeriesPoint point) {
        this.affectedPoints.add(point);
    }

    public long getDurationMinutes() {
        Instant end = recoveredAt != null ? recoveredAt : Instant.now();
        return java.time.Duration.between(triggeredAt, end).toMinutes();
    }
}
