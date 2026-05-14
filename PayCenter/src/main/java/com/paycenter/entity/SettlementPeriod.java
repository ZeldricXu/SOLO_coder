package com.paycenter.entity;

import com.paycenter.enums.PeriodType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_periods")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementPeriod {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String periodId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PeriodType periodType;

    @Column(columnDefinition = "TEXT")
    private String periodConfig;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal minSettlementAmount;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (minSettlementAmount == null) {
            minSettlementAmount = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
