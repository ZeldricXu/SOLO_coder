package com.logistics.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "couriers")
public class Courier {

    @Id
    @Column(name = "courier_id", nullable = false, unique = true)
    private String courierId;

    @Column(name = "courier_name", nullable = false)
    private String courierName;

    @Column(name = "courier_phone", nullable = false)
    private String courierPhone;

    @Column(name = "courier_station", nullable = false)
    private String courierStation;

    @Column(name = "courier_status", nullable = false)
    private String courierStatus;

    @Column(name = "courier_capacity", nullable = false)
    private Integer courierCapacity;

    @Column(name = "courier_current", nullable = false)
    private Integer courierCurrent;

    @Column(name = "courier_rating")
    private Double courierRating;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (courierCurrent == null) {
            courierCurrent = 0;
        }
        if (courierRating == null) {
            courierRating = 0.0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
