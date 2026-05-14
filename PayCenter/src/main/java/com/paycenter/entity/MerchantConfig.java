package com.paycenter.entity;

import com.paycenter.enums.FailoverStrategyType;
import com.paycenter.enums.PeriodType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "merchant_configs", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"merchantId"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FailoverStrategyType failoverStrategyType;

    @Column(nullable = false)
    private Integer failoverThreshold;

    @Column(nullable = false)
    private Integer failoverRetryInterval;

    @Column(nullable = false)
    private Integer failoverMaxRetryCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PeriodType settlementPeriodType;

    @Column(columnDefinition = "TEXT")
    private String settlementPeriodConfig;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal minSettlementAmount;

    @Column(nullable = false)
    private Boolean autoSettlementEnabled;

    @Column(columnDefinition = "TEXT")
    private String extraConfig;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (failoverStrategyType == null) {
            failoverStrategyType = FailoverStrategyType.NORMAL;
        }
        if (failoverThreshold == null) {
            failoverThreshold = 3;
        }
        if (failoverRetryInterval == null) {
            failoverRetryInterval = 5000;
        }
        if (failoverMaxRetryCount == null) {
            failoverMaxRetryCount = 3;
        }
        if (settlementPeriodType == null) {
            settlementPeriodType = PeriodType.DAILY;
        }
        if (minSettlementAmount == null) {
            minSettlementAmount = BigDecimal.ZERO;
        }
        if (autoSettlementEnabled == null) {
            autoSettlementEnabled = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
