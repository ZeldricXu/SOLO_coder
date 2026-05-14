package com.medical.appointment.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "hospitals")
public class Hospital {
    
    @Id
    @Column(name = "hospital_id")
    private String hospitalId;
    
    @Column(name = "hospital_name", nullable = false)
    private String hospitalName;
    
    @Column(name = "hospital_type")
    private String hospitalType;
    
    @Column(name = "hospital_address")
    private String hospitalAddress;
    
    @Column(name = "hospital_level")
    private String hospitalLevel;
    
    @Column(name = "hospital_status")
    private String hospitalStatus;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Hospital() {}

    public String getHospitalId() {
        return hospitalId;
    }

    public void setHospitalId(String hospitalId) {
        this.hospitalId = hospitalId;
    }

    public String getHospitalName() {
        return hospitalName;
    }

    public void setHospitalName(String hospitalName) {
        this.hospitalName = hospitalName;
    }

    public String getHospitalType() {
        return hospitalType;
    }

    public void setHospitalType(String hospitalType) {
        this.hospitalType = hospitalType;
    }

    public String getHospitalAddress() {
        return hospitalAddress;
    }

    public void setHospitalAddress(String hospitalAddress) {
        this.hospitalAddress = hospitalAddress;
    }

    public String getHospitalLevel() {
        return hospitalLevel;
    }

    public void setHospitalLevel(String hospitalLevel) {
        this.hospitalLevel = hospitalLevel;
    }

    public String getHospitalStatus() {
        return hospitalStatus;
    }

    public void setHospitalStatus(String hospitalStatus) {
        this.hospitalStatus = hospitalStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
