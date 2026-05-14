package com.fooddelivery.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @Column(name = "order_id")
    private String orderId;

    @Column(name = "restaurant_id", nullable = false)
    private String restaurantId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "order_amount", nullable = false)
    private Double orderAmount;

    @Column(name = "delivery_fee")
    private Double deliveryFee;

    @Column(name = "delivery_address", nullable = false)
    private String deliveryAddress;

    @Column(name = "delivery_region")
    private String deliveryRegion;

    @Column(name = "order_status")
    private String orderStatus;

    @Column(name = "payment_status")
    private String paymentStatus;

    @Column(name = "order_urgency")
    private String orderUrgency;

    @Column(name = "has_review")
    private Boolean hasReview;

    @Column(name = "order_time")
    private LocalDateTime orderTime;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (orderTime == null) {
            orderTime = LocalDateTime.now();
        }
        if (hasReview == null) {
            hasReview = false;
        }
        if (orderUrgency == null) {
            orderUrgency = "normal";
        }
    }
}
