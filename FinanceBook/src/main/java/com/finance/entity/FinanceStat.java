package com.finance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "finance_stats")
public class FinanceStat {

    @Id
    @Column(name = "stat_id", nullable = false, length = 50)
    private String statId;

    @Column(name = "account_id", nullable = false, length = 50)
    private String accountId;

    @Column(name = "stat_month", nullable = false, length = 20)
    private String statMonth;

    @Column(name = "record_count", nullable = false)
    private Long recordCount;

    @Column(name = "income_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal incomeTotal;

    @Column(name = "expense_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal expenseTotal;

    @Column(name = "category_stat", columnDefinition = "TEXT")
    private String categoryStat;
}
