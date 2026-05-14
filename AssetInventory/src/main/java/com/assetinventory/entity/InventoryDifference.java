package com.assetinventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "inventory_differences")
public class InventoryDifference {

    @Id
    @Column(name = "diff_id", nullable = false, length = 50)
    private String diffId;

    @Column(name = "count_id", nullable = false, length = 50)
    private String countId;

    @Column(name = "asset_id", nullable = false, length = 50)
    private String assetId;

    @Column(name = "diff_type", nullable = false, length = 50)
    private String diffType;

    @Column(name = "diff_system", nullable = false)
    private int diffSystem;

    @Column(name = "diff_actual", nullable = false)
    private int diffActual;

    @Column(name = "diff_value", nullable = false)
    private int diffValue;

    @Column(name = "diff_status", nullable = false, length = 50)
    private String diffStatus;

    @Column(name = "diff_time", nullable = false)
    private Instant diffTime;

    public InventoryDifference() {
    }

    public String getDiffId() {
        return diffId;
    }

    public void setDiffId(String diffId) {
        this.diffId = diffId;
    }

    public String getCountId() {
        return countId;
    }

    public void setCountId(String countId) {
        this.countId = countId;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getDiffType() {
        return diffType;
    }

    public void setDiffType(String diffType) {
        this.diffType = diffType;
    }

    public int getDiffSystem() {
        return diffSystem;
    }

    public void setDiffSystem(int diffSystem) {
        this.diffSystem = diffSystem;
    }

    public int getDiffActual() {
        return diffActual;
    }

    public void setDiffActual(int diffActual) {
        this.diffActual = diffActual;
    }

    public int getDiffValue() {
        return diffValue;
    }

    public void setDiffValue(int diffValue) {
        this.diffValue = diffValue;
    }

    public String getDiffStatus() {
        return diffStatus;
    }

    public void setDiffStatus(String diffStatus) {
        this.diffStatus = diffStatus;
    }

    public Instant getDiffTime() {
        return diffTime;
    }

    public void setDiffTime(Instant diffTime) {
        this.diffTime = diffTime;
    }
}
