package com.paycenter.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "transaction_stats", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"merchantId", "statDate"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionStat {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String statId;

    @Column(nullable = false, length = 64)
    private String merchantId;

    @Column(nullable = false)
    private LocalDate statDate;

    @Column(nullable = false)
    private Integer transactionCount;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private Integer successCount;

    @Column(nullable = false)
    private Integer failCount;

    @Column(nullable = false)
    private Integer refundCount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (transactionCount == null) {
            transactionCount = 0;
        }
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }
        if (successCount == null) {
            successCount = 0;
        }
        if (failCount == null) {
            failCount = 0;
        }
        if (refundCount == null) {
            refundCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
