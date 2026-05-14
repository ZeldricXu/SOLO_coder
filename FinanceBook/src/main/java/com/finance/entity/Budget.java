package com.finance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "budgets")
public class Budget {

    @Id
    @Column(name = "budget_id", nullable = false, length = 50)
    private String budgetId;

    @Column(name = "account_id", nullable = false, length = 50)
    private String accountId;

    @Column(name = "budget_category", nullable = false, length = 50)
    private String budgetCategory;

    @Column(name = "budget_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal budgetAmount;

    @Column(name = "budget_period", nullable = false, length = 20)
    private String budgetPeriod;

    @Column(name = "budget_used", nullable = false, precision = 19, scale = 2)
    private BigDecimal budgetUsed;

    @Column(name = "budget_remaining", nullable = false, precision = 19, scale = 2)
    private BigDecimal budgetRemaining;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
