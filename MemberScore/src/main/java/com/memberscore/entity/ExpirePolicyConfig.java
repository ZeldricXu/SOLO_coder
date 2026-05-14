package com.memberscore.entity;

import com.memberscore.enums.ExpirePolicyType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "expire_policy_configs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpirePolicyConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "policy_id", unique = true, nullable = false)
    private String policyId;
    
    @Column(name = "policy_name", nullable = false)
    private String policyName;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "policy_type", nullable = false)
    private ExpirePolicyType policyType;
    
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;
    
    @Column(name = "fixed_expire_days")
    private Integer fixedExpireDays;
    
    @Column(name = "flexible_base_days")
    private Integer flexibleBaseDays;
    
    @Column(name = "flexible_max_days")
    private Integer flexibleMaxDays;
    
    @Column(name = "level_expire_config", columnDefinition = "TEXT")
    private String levelExpireConfig;
    
    @Column(name = "point_threshold")
    private Integer pointThreshold;
    
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
        if (isDefault == null) {
            isDefault = false;
        }
    }
}
