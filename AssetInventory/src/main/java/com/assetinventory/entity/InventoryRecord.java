package com.assetinventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "inventory_records")
public class InventoryRecord {

    @Id
    @Column(name = "count_id", nullable = false, length = 50)
    private String countId;

    @Column(name = "task_id", nullable = false, length = 50)
    private String taskId;

    @Column(name = "asset_id", nullable = false, length = 50)
    private String assetId;

    @Column(name = "count_person", nullable = false, length = 50)
    private String countPerson;

    @Column(name = "count_quantity", nullable = false)
    private int countQuantity;

    @Column(name = "count_location", nullable = false, length = 200)
    private String countLocation;

    @Column(name = "count_status", nullable = false, length = 50)
    private String countStatus;

    @Column(name = "count_time", nullable = false)
    private Instant countTime;

    public InventoryRecord() {
    }

    public String getCountId() {
        return countId;
    }

    public void setCountId(String countId) {
        this.countId = countId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getCountPerson() {
        return countPerson;
    }

    public void setCountPerson(String countPerson) {
        this.countPerson = countPerson;
    }

    public int getCountQuantity() {
        return countQuantity;
    }

    public void setCountQuantity(int countQuantity) {
        this.countQuantity = countQuantity;
    }

    public String getCountLocation() {
        return countLocation;
    }

    public void setCountLocation(String countLocation) {
        this.countLocation = countLocation;
    }

    public String getCountStatus() {
        return countStatus;
    }

    public void setCountStatus(String countStatus) {
        this.countStatus = countStatus;
    }

    public Instant getCountTime() {
        return countTime;
    }

    public void setCountTime(Instant countTime) {
        this.countTime = countTime;
    }
}
