package com.paygateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "notification_retry")
public class NotificationRetry {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "retry_id", nullable = false, length = 64, unique = true)
    private String retryId;
    
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;
    
    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;
    
    @Column(name = "notify_url", nullable = false, length = 500)
    private String notifyUrl;
    
    @Column(name = "notify_content", columnDefinition = "TEXT")
    private String notifyContent;
    
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;
    
    @Column(name = "max_retry_count", nullable = false)
    private Integer maxRetryCount = 5;
    
    @Column(name = "status", nullable = false, length = 32)
    private String status = "pending";
    
    @Column(name = "last_error_msg", columnDefinition = "TEXT")
    private String lastErrorMsg;
    
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;
    
    @Column(name = "last_notify_at")
    private LocalDateTime lastNotifyAt;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
