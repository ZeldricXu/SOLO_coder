package com.adplatform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ad_budget")
public class AdBudget {
    @Id
    @Column(name = "budget_id", length = 50)
    private String budgetId;

    @Column(name = "ad_id", length = 50, nullable = false)
    private String adId;

    @Column(name = "budget_type", length = 50, nullable = false)
    private String budgetType;

    @Column(name = "budget_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal budgetAmount;

    @Column(name = "budget_consumed", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal budgetConsumed = BigDecimal.ZERO;

    @Column(name = "budget_remaining", precision = 12, scale = 2, nullable = false)
    private BigDecimal budgetRemaining;

    @Column(name = "budget_threshold", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal budgetThreshold = BigDecimal.ZERO;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (budgetRemaining == null) {
            budgetRemaining = budgetAmount;
        }
        if (budgetConsumed == null) {
            budgetConsumed = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
