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
@Table(name = "reports")
public class Report {

    @Id
    @Column(name = "report_id", nullable = false, length = 50)
    private String reportId;

    @Column(name = "account_id", nullable = false, length = 50)
    private String accountId;

    @Column(name = "report_period", nullable = false, length = 20)
    private String reportPeriod;

    @Column(name = "report_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal reportIncome;

    @Column(name = "report_expense", nullable = false, precision = 19, scale = 2)
    private BigDecimal reportExpense;

    @Column(name = "report_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal reportBalance;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;
}
