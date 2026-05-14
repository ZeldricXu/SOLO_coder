package com.paycenter.entity;

import com.paycenter.enums.ChannelType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTask {
    @Id
    @Column(length = 64, nullable = false, unique = true)
    private String taskId;

    @Column(nullable = false, length = 64)
    private String transactionId;

    @Column(nullable = false, length = 64)
    private String merchantId;

    @Column(nullable = false, length = 128)
    private String orderNo;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 64)
    private String channelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChannelType channelType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskStatus status;

    @Column(nullable = false)
    private Integer retryCount;

    @Column(nullable = false)
    private Integer maxRetryCount;

    private LocalDateTime nextRetryAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    private LocalDateTime completedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (retryCount == null) {
            retryCount = 0;
        }
        if (maxRetryCount == null) {
            maxRetryCount = 3;
        }
        if (status == null) {
            status = TaskStatus.PENDING;
        }
    }

    public enum TaskStatus {
        PENDING,
        PROCESSING,
        SUCCESS,
        FAILED,
        RETRY
    }
}
