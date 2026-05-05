package com.paygateway.entity;

import com.paygateway.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "payment_order", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"orderId"}),
    @UniqueConstraint(columnNames = {"merchantId", "merchantOrderNo"})
})
public class PaymentOrder {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;
    
    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;
    
    @Column(name = "merchant_order_no", nullable = false, length = 64)
    private String merchantOrderNo;
    
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;
    
    @Column(name = "currency", length = 8)
    private String currency = "CNY";
    
    @Column(name = "channel", nullable = false, length = 32)
    private String channel;
    
    @Column(name = "channel_order_no", length = 128)
    private String channelOrderNo;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private OrderStatus status = OrderStatus.PENDING;
    
    @Column(name = "product_desc", length = 255)
    private String productDesc;
    
    @Column(name = "notify_url", length = 255)
    private String notifyUrl;
    
    @Column(name = "paid_at")
    private LocalDateTime paidAt;
    
    @Column(name = "callback_received")
    private Boolean callbackReceived = false;
    
    @Version
    @Column(name = "version")
    private Integer version = 0;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    public void updateStatus(OrderStatus newStatus) {
        if (this.status == newStatus) {
            return;
        }
        
        if (OrderStatus.isPaid(this.status) && !OrderStatus.REFUNDED.equals(newStatus) 
                && !OrderStatus.PARTIAL_REFUNDED.equals(newStatus)) {
            throw new IllegalStateException("已支付订单状态不可变更为：" + newStatus);
        }
        
        this.status = newStatus;
    }
    
    public boolean isPaid() {
        return OrderStatus.isPaid(this.status);
    }
    
    public boolean canPay() {
        return OrderStatus.canPay(this.status);
    }
    
    public boolean canRefund() {
        return OrderStatus.canRefund(this.status);
    }
}
