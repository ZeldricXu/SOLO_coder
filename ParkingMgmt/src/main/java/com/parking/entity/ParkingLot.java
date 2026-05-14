package com.parking.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "parking_lots")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParkingLot {
    @Id
    @Column(name = "parking_id", nullable = false, length = 50)
    private String parkingId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String address;

    @Column(name = "total_spaces", nullable = false)
    private int totalSpaces;

    @Column(name = "hourly_rate", nullable = false)
    private double hourlyRate = 10.0;

    @Column(name = "charging_type", nullable = false, length = 20)
    private String chargingType = "hourly";

    @Column(name = "fixed_fee")
    private Double fixedFee;

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

    @OneToMany(mappedBy = "parkingLot", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ParkingSpace> spaces = new ArrayList<>();
}
