package com.paycenter.entity;

import com.paycenter.enums.TransactionStatus;
import com.paycenter.enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String transactionId;

    @Column(nullable = false, length = 64)
    private String merchantId;

    @Column(nullable = false, length = 128, unique = true)
    private String orderNo;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 64)
    private String channelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransactionStatus status;

    @Column(precision = 18, scale = 2)
    private BigDecimal refundedAmount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime paidAt;

    @Column(nullable = false)
    private Boolean notifyReceived;

    @Column(columnDefinition = "TEXT")
    private String notifyData;

    @Column(length = 512)
    private String failureReason;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (refundedAmount == null) {
            refundedAmount = BigDecimal.ZERO;
        }
        if (notifyReceived == null) {
            notifyReceived = false;
        }
    }
}
