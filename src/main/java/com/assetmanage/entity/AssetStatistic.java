package com.assetmanage.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "asset_statistics")
public class AssetStatistic {

    @Id
    @Column(name = "stat_id", length = 64)
    private String statId;

    @Column(name = "stat_date")
    private LocalDate statDate;

    @Column(name = "total_assets")
    private Integer totalAssets;

    @Column(name = "in_use_assets")
    private Integer inUseAssets;

    @Column(name = "idle_assets")
    private Integer idleAssets;

    @Column(name = "maintenance_assets")
    private Integer maintenanceAssets;

    @Column(name = "scraped_assets")
    private Integer scrapedAssets;

    @Column(name = "total_value", precision = 18, scale = 2)
    private BigDecimal totalValue;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.statDate == null) {
            this.statDate = LocalDate.now();
        }
    }
}
