package com.assetmanage.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "inventory_differences")
public class InventoryDifference {

    @Id
    @Column(name = "diff_id", length = 64)
    private String diffId;

    @Column(name = "check_id")
    private String checkId;

    @Column(name = "asset_id")
    private String assetId;

    @Column(name = "system_location")
    private String systemLocation;

    @Column(name = "actual_location")
    private String actualLocation;

    @Column(name = "diff_type")
    private String diffType;

    @Column(name = "diff_status")
    private String diffStatus;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
