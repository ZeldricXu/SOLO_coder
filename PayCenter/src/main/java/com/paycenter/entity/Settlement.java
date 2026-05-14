package com.paycenter.entity;

import com.paycenter.enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlements")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Settlement {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String settlementId;

    @Column(nullable = false, length = 64)
    private String merchantId;

    @Column(nullable = false)
    private LocalDate settlementPeriod;

    @Column(nullable = false)
    private Integer transactionCount;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal settlementAmount;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SettlementStatus settlementStatus;

    private LocalDateTime settledAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(length = 512)
    private String failureReason;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
