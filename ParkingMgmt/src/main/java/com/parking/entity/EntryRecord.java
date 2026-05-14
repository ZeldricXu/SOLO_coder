package com.parking.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "entry_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntryRecord {
    @Id
    @Column(name = "entry_id", nullable = false, length = 50)
    private String entryId;

    @Column(name = "vehicle_id", nullable = false, length = 50)
    private String vehicleId;

    @Column(name = "space_id", nullable = false, length = 50)
    private String spaceId;

    @Column(name = "parking_id", nullable = false, length = 50)
    private String parkingId;

    @Column(name = "vehicle_number", length = 20)
    private String vehicleNumber;

    @Column(name = "space_number", length = 20)
    private String spaceNumber;

    @Column(name = "entry_time", nullable = false)
    private LocalDateTime entryTime;

    @Column(name = "entry_status", nullable = false, length = 20)
    private String entryStatus = "parked";

    @Column(name = "vehicle_type", length = 50)
    private String vehicleType = "standard";

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
