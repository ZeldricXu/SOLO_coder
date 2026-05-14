package com.assetmanage.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "maintenance_records")
public class MaintenanceRecord {

    @Id
    @Column(name = "maintenance_id", length = 64)
    private String maintenanceId;

    @Column(name = "asset_id")
    private String assetId;

    @Column(name = "maintenance_type")
    private String maintenanceType;

    @Column(name = "maintenance_date")
    private LocalDate maintenanceDate;

    @Column(name = "maintenance_content", columnDefinition = "TEXT")
    private String maintenanceContent;

    @Column(name = "maintenance_cost", precision = 18, scale = 2)
    private BigDecimal maintenanceCost;

    @Column(name = "next_maintenance")
    private LocalDate nextMaintenance;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
