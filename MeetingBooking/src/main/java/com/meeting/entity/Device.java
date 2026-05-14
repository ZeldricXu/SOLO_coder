package com.meeting.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "devices")
public class Device {
    @Id
    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "device_type", nullable = false)
    private String deviceType;

    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "device_status", nullable = false)
    private String deviceStatus;

    @Column(name = "device_features")
    private String deviceFeatures;

    @Column(name = "last_maintenance")
    private LocalDateTime lastMaintenance;

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
