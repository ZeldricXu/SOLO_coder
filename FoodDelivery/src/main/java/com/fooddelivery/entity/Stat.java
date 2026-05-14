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
@Table(name = "stats")
public class Stat {
    @Id
    @Column(name = "stat_id")
    private String statId;

    @Column(name = "stat_month", nullable = false, unique = true)
    private String statMonth;

    @Column(name = "order_count")
    private Integer orderCount;

    @Column(name = "delivery_count")
    private Integer deliveryCount;

    @Column(name = "cancel_count")
    private Integer cancelCount;

    @Column(name = "avg_delivery_time")
    private Double avgDeliveryTime;

    @Column(name = "total_delivery_time")
    private Long totalDeliveryTime;

    @Column(name = "total_amount")
    private Double totalAmount;

    @Column(name = "review_count")
    private Integer reviewCount;

    @Column(name = "avg_rating")
    private Double avgRating;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
        if (orderCount == null) orderCount = 0;
        if (deliveryCount == null) deliveryCount = 0;
        if (cancelCount == null) cancelCount = 0;
        if (avgDeliveryTime == null) avgDeliveryTime = 0.0;
        if (totalDeliveryTime == null) totalDeliveryTime = 0L;
        if (totalAmount == null) totalAmount = 0.0;
        if (reviewCount == null) reviewCount = 0;
        if (avgRating == null) avgRating = 0.0;
    }
}
