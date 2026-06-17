package com.enterprise.risk.storage.entity;

import com.enterprise.risk.common.alert.AlertSeverity;
import com.enterprise.risk.common.rule.RuleType;
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
@Table(name = "risk_rules", indexes = {
        @Index(name = "idx_rule_business_enabled", columnList = "business_line, enabled"),
        @Index(name = "idx_rule_type", columnList = "rule_type"),
        @Index(name = "idx_rule_priority", columnList = "priority")
})
public class RuleEntity implements Serializable {

    @Id
    @Column(name = "rule_id", length = 64, nullable = false)
    private String ruleId;

    @Column(name = "rule_name", length = 256, nullable = false)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", length = 32, nullable = false)
    private RuleType ruleType;

    @Column(name = "business_line", length = 64, nullable = false)
    private String businessLine;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_types", columnDefinition = "jsonb")
    private List<String> eventTypes;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @Column(name = "short_circuit", nullable = false)
    @Builder.Default
    private Boolean shortCircuit = false;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 32, nullable = false)
    @Builder.Default
    private AlertSeverity severity = AlertSeverity.MEDIUM;

    @Column(name = "dsl_expression", columnDefinition = "text")
    private String dslExpression;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "window_config", columnDefinition = "jsonb")
    private Map<String, Object> windowConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sequence_config", columnDefinition = "jsonb")
    private Map<String, Object> sequenceConfig;

    @Column(name = "model_weight")
    @Builder.Default
    private Double modelWeight = 0.5;

    @Column(name = "threshold")
    @Builder.Default
    private Double threshold = 0.7;

    @Column(name = "escalation_threshold")
    private Integer escalationThreshold;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suppression_rules", columnDefinition = "jsonb")
    private List<String> suppressionRuleIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions", columnDefinition = "jsonb")
    private List<String> actionIds;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Long createdAt = Instant.now().toEpochMilli();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Long updatedAt = Instant.now().toEpochMilli();

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now().toEpochMilli();
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
