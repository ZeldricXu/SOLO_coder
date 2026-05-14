package com.logistics.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "logistics")
public class Logistics {

    @Id
    @Column(name = "logistics_id", nullable = false, unique = true)
    private String logisticsId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "logistics_number", nullable = false, unique = true)
    private String logisticsNumber;

    @Column(name = "logistics_status", nullable = false)
    private String logisticsStatus;

    @Column(name = "station_id", nullable = false)
    private String stationId;

    @Column(name = "courier_id")
    private String courierId;

    @Column(name = "delivery_type_code", nullable = false)
    private String deliveryTypeCode;

    @Column(name = "shipping_time")
    private LocalDateTime shippingTime;

    @Column(name = "delivery_time")
    private LocalDateTime deliveryTime;

    @Column(name = "logistics_fee")
    private Double logisticsFee;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (deliveryTypeCode == null) {
            deliveryTypeCode = "STANDARD";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
