package com.assetmanage.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "depreciation_records")
public class DepreciationRecord {

    @Id
    @Column(name = "depreciation_id", length = 64)
    private String depreciationId;

    @Column(name = "asset_id")
    private String assetId;

    @Column(name = "depreciation_period", length = 20)
    private String depreciationPeriod;

    @Column(name = "depreciation_value", precision = 18, scale = 2)
    private BigDecimal depreciationValue;

    @Column(name = "accumulated_depreciation", precision = 18, scale = 2)
    private BigDecimal accumulatedDepreciation;

    @Column(name = "current_value", precision = 18, scale = 2)
    private BigDecimal currentValue;

    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt;

    @PrePersist
    public void prePersist() {
        this.calculatedAt = LocalDateTime.now();
    }
}
