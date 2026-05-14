package com.memberscore.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "point_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointRule {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "rule_id", unique = true, nullable = false)
    private String ruleId;
    
    @Column(name = "rule_name", nullable = false)
    private String ruleName;
    
    @Column(name = "rule_type", nullable = false)
    private String ruleType;
    
    @Column(name = "rule_points", nullable = false)
    private Integer rulePoints;
    
    @Column(name = "rule_multiplier", nullable = false)
    private Double ruleMultiplier;
    
    @Column(name = "rule_enabled", nullable = false)
    private Boolean ruleEnabled;
    
    @Column(name = "rule_description")
    private String ruleDescription;
    
    @Column(name = "start_date")
    private LocalDateTime startDate;
    
    @Column(name = "end_date")
    private LocalDateTime endDate;
    
    @Column(name = "validation_rule_id")
    private String validationRuleId;
    
    @Column(name = "expire_policy_id")
    private String expirePolicyId;
    
    @Column(name = "rule_config", columnDefinition = "TEXT")
    private String ruleConfig;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (ruleMultiplier == null) {
            ruleMultiplier = 1.0;
        }
        if (ruleEnabled == null) {
            ruleEnabled = true;
        }
    }
}
