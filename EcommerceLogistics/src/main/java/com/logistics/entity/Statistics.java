package com.logistics.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "statistics")
public class Statistics {

    @Id
    @Column(name = "stat_id", nullable = false, unique = true)
    private String statId;

    @Column(name = "stat_month", nullable = false, unique = true)
    private String statMonth;

    @Column(name = "logistics_count", nullable = false)
    private Long logisticsCount;

    @Column(name = "delivery_count", nullable = false)
    private Long deliveryCount;

    @Column(name = "delivering_count", nullable = false)
    private Long deliveringCount;

    @Column(name = "avg_delivery_time")
    private Double avgDeliveryTime;

    @Column(name = "total_fee", nullable = false)
    private Double totalFee;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (logisticsCount == null) {
            logisticsCount = 0L;
        }
        if (deliveryCount == null) {
            deliveryCount = 0L;
        }
        if (deliveringCount == null) {
            deliveringCount = 0L;
        }
        if (totalFee == null) {
            totalFee = 0.0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
