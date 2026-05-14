package com.fitnesscenter.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "equipment")
public class Equipment {

    @Id
    @Column(name = "equipment_id")
    private String equipmentId;

    @Column(name = "equipment_name", nullable = false)
    private String equipmentName;

    @Column(name = "equipment_type")
    private String equipmentType;

    @Column(name = "equipment_status")
    private String equipmentStatus;

    @Column(name = "gym_id")
    private String gymId;

    @Column(name = "last_maintenance")
    private Instant lastMaintenance;

    @Column(name = "purchase_date")
    private Instant purchaseDate;

    public Equipment() {}

    public String getEquipmentId() {
        return equipmentId;
    }

    public void setEquipmentId(String equipmentId) {
        this.equipmentId = equipmentId;
    }

    public String getEquipmentName() {
        return equipmentName;
    }

    public void setEquipmentName(String equipmentName) {
        this.equipmentName = equipmentName;
    }

    public String getEquipmentType() {
        return equipmentType;
    }

    public void setEquipmentType(String equipmentType) {
        this.equipmentType = equipmentType;
    }

    public String getEquipmentStatus() {
        return equipmentStatus;
    }

    public void setEquipmentStatus(String equipmentStatus) {
        this.equipmentStatus = equipmentStatus;
    }

    public String getGymId() {
        return gymId;
    }

    public void setGymId(String gymId) {
        this.gymId = gymId;
    }

    public Instant getLastMaintenance() {
        return lastMaintenance;
    }

    public void setLastMaintenance(Instant lastMaintenance) {
        this.lastMaintenance = lastMaintenance;
    }

    public Instant getPurchaseDate() {
        return purchaseDate;
    }

    public void setPurchaseDate(Instant purchaseDate) {
        this.purchaseDate = purchaseDate;
    }
}
