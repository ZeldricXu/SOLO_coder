package com.assetmanage.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "scrap_records")
public class ScrapRecord {

    @Id
    @Column(name = "scrap_id", length = 64)
    private String scrapId;

    @Column(name = "asset_id")
    private String assetId;

    @Column(name = "scrap_reason", columnDefinition = "TEXT")
    private String scrapReason;

    @Column(name = "scrap_status")
    private String scrapStatus;

    @Column(name = "residual_value", precision = 18, scale = 2)
    private BigDecimal residualValue;

    @Column(name = "scrap_time")
    private LocalDateTime scrapTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.scrapTime == null) {
            this.scrapTime = LocalDateTime.now();
        }
    }
}
