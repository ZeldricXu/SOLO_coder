package com.enterprise.risk.common.alert;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertEvent implements Serializable {

    @JsonProperty("alert_id")
    private String alertId;

    @JsonProperty("fingerprint")
    private String fingerprint;

    @JsonProperty("rule_id")
    private String ruleId;

    @JsonProperty("rule_name")
    private String ruleName;

    @JsonProperty("severity")
    private AlertSeverity severity;

    @JsonProperty("entity_id")
    private String entityId;

    @JsonProperty("entity_type")
    private String entityType;

    @JsonProperty("business_line")
    private String businessLine;

    @JsonProperty("description")
    private String description;

    @JsonProperty("risk_score")
    @Builder.Default
    private Double riskScore = 0.0;

    @JsonProperty("rule_hit_count")
    @Builder.Default
    private Integer ruleHitCount = 1;

    @JsonProperty("event_count")
    @Builder.Default
    private Integer eventCount = 1;

    @JsonProperty("first_event_time")
    private Long firstEventTime;

    @JsonProperty("last_event_time")
    private Long lastEventTime;

    @JsonProperty("created_at")
    @Builder.Default
    private Long createdAt = Instant.now().toEpochMilli();

    @JsonProperty("status")
    @Builder.Default
    private AlertStatus status = AlertStatus.OPEN;

    @JsonProperty("triggered_events")
    @Builder.Default
    private List<String> triggeredEventIds = new ArrayList<>();

    @JsonProperty("suppressed_by")
    private String suppressedBy;

    @JsonProperty("metadata")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @JsonProperty("actions")
    @Builder.Default
    private List<String> actions = new ArrayList<>();

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

    public void addTriggeredEvent(String eventId) {
        if (!triggeredEventIds.contains(eventId)) {
            triggeredEventIds.add(eventId);
        }
    }

    public boolean shouldEscalate(int threshold) {
        return this.ruleHitCount >= threshold;
    }

    public AlertSeverity escalate() {
        AlertSeverity[] values = AlertSeverity.values();
        int currentIndex = this.severity.ordinal();
        if (currentIndex < values.length - 1) {
            this.severity = values[currentIndex + 1];
        }
        return this.severity;
    }
}
