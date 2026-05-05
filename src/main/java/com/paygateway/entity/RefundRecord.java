package com.paygateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "refund_record", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"refundId"}),
    @UniqueConstraint(columnNames = {"merchantId", "merchantRefundNo"})
})
public class RefundRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "refund_id", nullable = false, length = 64)
    private String refundId;
    
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;
    
    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;
    
    @Column(name = "merchant_refund_no", nullable = false, length = 64)
    private String merchantRefundNo;
    
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;
    
    @Column(name = "reason", length = 255)
    private String reason;
    
    @Column(name = "channel_refund_no", length = 128)
    private String channelRefundNo;
    
    @Column(name = "status", length = 32)
    private String status = "pending";
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
