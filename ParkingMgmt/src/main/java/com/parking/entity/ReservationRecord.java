package com.parking.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "reservation_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRecord {
    @Id
    @Column(name = "reserve_id", nullable = false, length = 50)
    private String reserveId;

    @Column(name = "space_id", nullable = false, length = 50)
    private String spaceId;

    @Column(name = "vehicle_id", nullable = false, length = 50)
    private String vehicleId;

    @Column(name = "parking_id", nullable = false, length = 50)
    private String parkingId;

    @Column(name = "vehicle_number", length = 20)
    private String vehicleNumber;

    @Column(name = "space_number", length = 20)
    private String spaceNumber;

    @Column(name = "reserve_time", nullable = false)
    private LocalDateTime reserveTime;

    @Column(name = "expected_start_time")
    private LocalDateTime expectedStartTime;

    @Column(name = "expected_end_time")
    private LocalDateTime expectedEndTime;

    @Column(name = "reserve_status", nullable = false, length = 20)
    private String reserveStatus = "confirmed";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
