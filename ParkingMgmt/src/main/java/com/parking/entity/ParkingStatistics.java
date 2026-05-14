package com.parking.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "parking_statistics")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParkingStatistics {
    @Id
    @Column(name = "stat_id", nullable = false, length = 50)
    private String statId;

    @Column(name = "stat_month", nullable = false, length = 7, unique = true)
    private String statMonth;

    @Column(name = "entry_count", nullable = false)
    private int entryCount = 0;

    @Column(name = "exit_count", nullable = false)
    private int exitCount = 0;

    @Column(name = "total_amount", nullable = false)
    private double totalAmount = 0.0;

    @Column(name = "reservation_count", nullable = false)
    private int reservationCount = 0;

    @Column(name = "occupancy_rate", nullable = false)
    private double occupancyRate = 0.0;

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
