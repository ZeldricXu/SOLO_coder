package com.example.mailservice.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "category_rule")
public class CategoryRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", unique = true, nullable = false, length = 64)
    private String ruleId;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "rule_pattern", columnDefinition = "TEXT")
    private String rulePattern;

    @Column(name = "target_category", nullable = false, length = 64)
    private String targetCategory;

    @Column(name = "rule_priority")
    private Integer rulePriority;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "match_count", columnDefinition = "INT DEFAULT 0")
    private Integer matchCount;

    @Column(name = "dynamic_priority")
    private Integer dynamicPriority;

    @Column(name = "last_matched_at")
    private LocalDateTime lastMatchedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) {
            enabled = true;
        }
        if (rulePriority == null) {
            rulePriority = 0;
        }
        if (matchCount == null) {
            matchCount = 0;
        }
        if (dynamicPriority == null) {
            dynamicPriority = rulePriority;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
