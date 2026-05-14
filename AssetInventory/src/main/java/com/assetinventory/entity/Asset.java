package com.assetinventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "assets")
public class Asset {

    @Id
    @Column(name = "asset_id", nullable = false, length = 50)
    private String assetId;

    @Column(name = "asset_name", nullable = false, length = 200)
    private String assetName;

    @Column(name = "asset_category", nullable = false, length = 100)
    private String assetCategory;

    @Column(name = "asset_quantity", nullable = false)
    private int assetQuantity;

    @Column(name = "asset_location", nullable = false, length = 200)
    private String assetLocation;

    @Column(name = "asset_status", nullable = false, length = 50)
    private String assetStatus;

    @Column(name = "asset_value", nullable = false)
    private double assetValue;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_counted_at")
    private Instant lastCountedAt;

    public Asset() {
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public String getAssetCategory() {
        return assetCategory;
    }

    public void setAssetCategory(String assetCategory) {
        this.assetCategory = assetCategory;
    }

    public int getAssetQuantity() {
        return assetQuantity;
    }

    public void setAssetQuantity(int assetQuantity) {
        this.assetQuantity = assetQuantity;
    }

    public String getAssetLocation() {
        return assetLocation;
    }

    public void setAssetLocation(String assetLocation) {
        this.assetLocation = assetLocation;
    }

    public String getAssetStatus() {
        return assetStatus;
    }

    public void setAssetStatus(String assetStatus) {
        this.assetStatus = assetStatus;
    }

    public double getAssetValue() {
        return assetValue;
    }

    public void setAssetValue(double assetValue) {
        this.assetValue = assetValue;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Instant getLastCountedAt() {
        return lastCountedAt;
    }

    public void setLastCountedAt(Instant lastCountedAt) {
        this.lastCountedAt = lastCountedAt;
    }
}
