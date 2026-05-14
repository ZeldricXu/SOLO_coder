package com.logistics.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "delivery_types")
public class DeliveryType {

    @Id
    @Column(name = "type_code", nullable = false, unique = true)
    private String typeCode;

    @Column(name = "type_name", nullable = false)
    private String typeName;

    @Column(name = "description")
    private String description;

    @Column(name = "urgency_level", nullable = false)
    private String urgencyLevel;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "base_fee", nullable = false)
    private Double baseFee;

    @Column(name = "distance_rate", nullable = false)
    private Double distanceRate;

    @Column(name = "time_rate", nullable = false)
    private Double timeRate;

    @Column(name = "min_fee", nullable = false)
    private Double minFee;

    @Column(name = "max_fee", nullable = false)
    private Double maxFee;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isActive == null) {
            isActive = true;
        }
        if (priority == null) {
            priority = 10;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
