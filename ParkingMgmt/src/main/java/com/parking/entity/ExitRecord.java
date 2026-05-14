package com.parking.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "exit_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExitRecord {
    @Id
    @Column(name = "exit_id", nullable = false, length = 50)
    private String exitId;

    @Column(name = "entry_id", nullable = false, length = 50)
    private String entryId;

    @Column(name = "vehicle_id", nullable = false, length = 50)
    private String vehicleId;

    @Column(name = "space_id", nullable = false, length = 50)
    private String spaceId;

    @Column(name = "exit_time", nullable = false)
    private LocalDateTime exitTime;

    @Column(name = "parking_duration", nullable = false)
    private int parkingDuration;

    @Column(name = "parking_fee", nullable = false)
    private double parkingFee;

    @Column(name = "exit_status", nullable = false, length = 20)
    private String exitStatus = "completed";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
