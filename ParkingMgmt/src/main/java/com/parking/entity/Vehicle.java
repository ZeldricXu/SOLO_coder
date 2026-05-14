package com.parking.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "vehicles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle {
    @Id
    @Column(name = "vehicle_id", nullable = false, length = 50)
    private String vehicleId;

    @Column(name = "vehicle_number", nullable = false, length = 20, unique = true)
    private String vehicleNumber;

    @Column(name = "vehicle_type", nullable = false, length = 20)
    private String vehicleType = "sedan";

    @Column(name = "vehicle_owner", length = 50)
    private String vehicleOwner;

    @Column(name = "vehicle_phone", length = 20)
    private String vehiclePhone;

    @Column(name = "current_status", length = 20)
    private String currentStatus = "idle";

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        registeredAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
