package com.parking.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "parking_space_type_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParkingSpaceTypeConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String spaceType;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private Double basePriceMultiplier;

    @Column(nullable = false)
    private Boolean canReserve;

    private String vehicleTypeRestriction;

    private String description;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) {
            enabled = true;
        }
        if (basePriceMultiplier == null) {
            basePriceMultiplier = 1.0;
        }
        if (canReserve == null) {
            canReserve = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
