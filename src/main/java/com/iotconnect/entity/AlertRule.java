package com.iotconnect.entity;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "alert_rules")
public class AlertRule {

    @Id
    @Column(name = "rule_id", length = 64)
    private String ruleId;

    @Column(name = "rule_name", length = 128, nullable = false)
    private String ruleName;

    @Column(name = "device_type", length = 64, nullable = false)
    private String deviceType;

    @Column(name = "metric", length = 64, nullable = false)
    private String metric;

    @Column(name = "threshold", nullable = false)
    private Double threshold;

    @Column(name = "operator", length = 32, nullable = false)
    private String operator;

    @Column(name = "severity", length = 32, nullable = false)
    private String severity;

    @ElementCollection
    @CollectionTable(name = "alert_rule_channels", joinColumns = @JoinColumn(name = "rule_id"))
    @Column(name = "notify_channel")
    private List<String> notifyChannels;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "silence_duration_seconds")
    private Integer silenceDurationSeconds;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AlertRule() {
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

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public List<String> getNotifyChannels() {
        return notifyChannels;
    }

    public void setNotifyChannels(List<String> notifyChannels) {
        this.notifyChannels = notifyChannels;
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

    public Integer getSilenceDurationSeconds() {
        return silenceDurationSeconds;
    }

    public void setSilenceDurationSeconds(Integer silenceDurationSeconds) {
        this.silenceDurationSeconds = silenceDurationSeconds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
