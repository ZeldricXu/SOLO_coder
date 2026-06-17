package com.enterprise.risk.storage.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rule_hit_logs", indexes = {
        @Index(name = "idx_hit_rule_time", columnList = "rule_id, hit_time"),
        @Index(name = "idx_hit_entity", columnList = "entity_id, entity_type"),
        @Index(name = "idx_hit_business", columnList = "business_line"),
        @Index(name = "idx_hit_event_id", columnList = "event_id"),
        @Index(name = "idx_hit_time", columnList = "hit_time")
})
public class RuleHitLogEntity implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "rule_id", length = 64, nullable = false)
    private String ruleId;

    @Column(name = "rule_name", length = 256, nullable = false)
    private String ruleName;

    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Column(name = "event_type", length = 128, nullable = false)
    private String eventType;

    @Column(name = "entity_id", length = 128, nullable = false)
    private String entityId;

    @Column(name = "entity_type", length = 64, nullable = false)
    private String entityType;

    @Column(name = "business_line", length = 64, nullable = false)
    private String businessLine;

    @Column(name = "hit_time", nullable = false)
    @Builder.Default
    private Long hitTime = Instant.now().toEpochMilli();

    @Column(name = "matched_value")
    private Double matchedValue;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(name = "risk_score")
    @Builder.Default
    private Double riskScore = 0.0;

    @Column(name = "alert_generated", nullable = false)
    @Builder.Default
    private Boolean alertGenerated = false;

    @Column(name = "alert_id", length = 64)
    private String alertId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hit_context", columnDefinition = "jsonb")
    private Map<String, Object> hitContext;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_attributes", columnDefinition = "jsonb")
    private Map<String, Object> eventAttributes;
}
