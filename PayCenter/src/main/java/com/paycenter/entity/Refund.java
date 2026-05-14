package com.paycenter.entity;

import com.paycenter.enums.RefundStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refunds")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Refund {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String refundId;

    @Column(nullable = false, length = 64)
    private String transactionId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal refundAmount;

    @Column(length = 512)
    private String refundReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundStatus refundStatus;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime refundedAt;

    @Column(length = 512)
    private String failureReason;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
