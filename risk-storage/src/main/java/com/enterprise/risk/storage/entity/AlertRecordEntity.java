package com.enterprise.risk.storage.entity;

import com.enterprise.risk.common.alert.AlertSeverity;
import com.enterprise.risk.common.alert.AlertStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "alert_records", indexes = {
        @Index(name = "idx_alert_fingerprint", columnList = "fingerprint"),
        @Index(name = "idx_alert_status_created", columnList = "status, created_at"),
        @Index(name = "idx_alert_rule_id", columnList = "rule_id"),
        @Index(name = "idx_alert_entity", columnList = "entity_id, entity_type"),
        @Index(name = "idx_alert_business", columnList = "business_line"),
        @Index(name = "idx_alert_severity", columnList = "severity"),
        @Index(name = "idx_alert_time_range", columnList = "created_at")
})
public class AlertRecordEntity implements Serializable {

    @Id
    @Column(name = "alert_id", length = 64, nullable = false)
    private String alertId;

    @Column(name = "fingerprint", length = 128, nullable = false)
    private String fingerprint;

    @Column(name = "rule_id", length = 64, nullable = false)
    private String ruleId;

    @Column(name = "rule_name", length = 256, nullable = false)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 32, nullable = false)
    private AlertSeverity severity;

    @Column(name = "entity_id", length = 128, nullable = false)
    private String entityId;

    @Column(name = "entity_type", length = 64, nullable = false)
    private String entityType;

    @Column(name = "business_line", length = 64, nullable = false)
    private String businessLine;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "risk_score")
    @Builder.Default
    private Double riskScore = 0.0;

    @Column(name = "rule_hit_count", nullable = false)
    @Builder.Default
    private Integer ruleHitCount = 1;

    @Column(name = "event_count", nullable = false)
    @Builder.Default
    private Integer eventCount = 1;

    @Column(name = "first_event_time")
    private Long firstEventTime;

    @Column(name = "last_event_time")
    private Long lastEventTime;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Long createdAt = Instant.now().toEpochMilli();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Long updatedAt = Instant.now().toEpochMilli();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    @Builder.Default
    private AlertStatus status = AlertStatus.OPEN;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "triggered_events", columnDefinition = "jsonb")
    private List<String> triggeredEventIds;

    @Column(name = "suppressed_by", length = 64)
    private String suppressedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions", columnDefinition = "jsonb")
    private List<String> actions;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now().toEpochMilli();
    }

    public void incrementEventCount() {
        this.eventCount++;
    }

    public void incrementRuleHitCount() {
        this.ruleHitCount++;
    }

    public void updateEventTime(Long eventTime) {
        if (this.firstEventTime == null || eventTime < this.firstEventTime) {
            this.firstEventTime = eventTime;
        }
        if (this.lastEventTime == null || eventTime > this.lastEventTime) {
            this.lastEventTime = eventTime;
        }
    }

    public boolean shouldEscalate(int threshold) {
        return this.ruleHitCount >= threshold;
    }

    public void escalateSeverity() {
        AlertSeverity[] values = AlertSeverity.values();
        int currentIndex = this.severity.ordinal();
        if (currentIndex < values.length - 1) {
            this.severity = values[currentIndex + 1];
        }
    }
}
