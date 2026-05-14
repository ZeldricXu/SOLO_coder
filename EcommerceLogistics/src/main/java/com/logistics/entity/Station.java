package com.logistics.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stations")
public class Station {

    @Id
    @Column(name = "station_id", nullable = false, unique = true)
    private String stationId;

    @Column(name = "station_name", nullable = false)
    private String stationName;

    @Column(name = "station_address", nullable = false)
    private String stationAddress;

    @Column(name = "station_region", nullable = false)
    private String stationRegion;

    @Column(name = "station_capacity", nullable = false)
    private Integer stationCapacity;

    @Column(name = "station_current", nullable = false)
    private Integer stationCurrent;

    @Column(name = "station_status", nullable = false)
    private String stationStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (stationCurrent == null) {
            stationCurrent = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
