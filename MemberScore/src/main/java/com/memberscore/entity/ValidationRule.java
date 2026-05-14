package com.memberscore.entity;

import com.memberscore.enums.ValidationType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "validation_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidationRule {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "rule_id", unique = true, nullable = false)
    private String ruleId;
    
    @Column(name = "rule_name", nullable = false)
    private String ruleName;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "validation_type", nullable = false)
    private ValidationType validationType;
    
    @Column(name = "source_type", nullable = false)
    private String sourceType;
    
    @Column(name = "min_amount")
    private Integer minAmount;
    
    @Column(name = "max_amount")
    private Integer maxAmount;
    
    @Column(name = "amount_factor")
    private Double amountFactor;
    
    @Column(name = "fixed_points")
    private Integer fixedPoints;
    
    @Column(name = "time_window_minutes")
    private Integer timeWindowMinutes;
    
    @Column(name = "max_points_per_window")
    private Integer maxPointsPerWindow;
    
    @Column(name = "validation_config", columnDefinition = "TEXT")
    private String validationConfig;
    
    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled;
    
    @Column(name = "description")
    private String description;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (isEnabled == null) {
            isEnabled = true;
        }
        if (amountFactor == null) {
            amountFactor = 1.0;
        }
    }
}
