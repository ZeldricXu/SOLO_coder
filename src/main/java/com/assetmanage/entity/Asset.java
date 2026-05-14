package com.assetmanage.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "assets")
public class Asset {

    @Id
    @Column(name = "asset_id", length = 64)
    private String assetId;

    @Column(name = "asset_name", nullable = false)
    private String assetName;

    @Column(name = "asset_type", nullable = false)
    private String assetType;

    @Column(name = "asset_category")
    private String assetCategory;

    @Column(name = "asset_model")
    private String assetModel;

    @Column(name = "asset_sn")
    private String assetSn;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "purchase_price", precision = 18, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "current_value", precision = 18, scale = 2)
    private BigDecimal currentValue;

    @Column(name = "depreciation_method")
    private String depreciationMethod;

    @Column(name = "depreciation_rate", precision = 10, scale = 4)
    private BigDecimal depreciationRate;

    @Column(name = "useful_life")
    private Integer usefulLife;

    @Column(name = "accumulated_depreciation", precision = 18, scale = 2)
    private BigDecimal accumulatedDepreciation;

    @Column(name = "asset_status")
    private String assetStatus;

    @Column(name = "location")
    private String location;

    @Column(name = "department")
    private String department;

    @Column(name = "current_user_id")
    private String currentUserId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.accumulatedDepreciation == null) {
            this.accumulatedDepreciation = BigDecimal.ZERO;
        }
        if (this.currentValue == null && this.purchasePrice != null) {
            this.currentValue = this.purchasePrice;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
