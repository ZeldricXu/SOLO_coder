package com.parking.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "parking_spaces")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParkingSpace {
    @Id
    @Column(name = "space_id", nullable = false, length = 50)
    private String spaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parking_id", nullable = false)
    private ParkingLot parkingLot;

    @Column(name = "space_number", nullable = false, length = 20)
    private String spaceNumber;

    @Column(name = "space_type", nullable = false, length = 20)
    private String spaceType = "standard";

    @Column(name = "space_status", nullable = false, length = 20)
    private String spaceStatus = "available";

    @Column(name = "space_price", nullable = false)
    private double spacePrice = 10.0;

    @Column(name = "occupied_time")
    private LocalDateTime occupiedTime;

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

    @Version
    private Long version;
}
