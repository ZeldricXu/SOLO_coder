package com.medical.appointment.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "patients")
public class Patient {
    
    @Id
    @Column(name = "patient_id")
    private String patientId;
    
    @Column(name = "patient_name", nullable = false)
    private String patientName;
    
    @Column(name = "patient_phone")
    private String patientPhone;
    
    @Column(name = "patient_id_number")
    private String patientIdNumber;
    
    @Column(name = "patient_status")
    private String patientStatus;
    
    @Column(name = "registered_at")
    private LocalDateTime registeredAt;
    
    @Column(name = "appointment_count")
    private Integer appointmentCount;
    
    @Column(name = "visit_count")
    private Integer visitCount;

    public Patient() {
        this.appointmentCount = 0;
        this.visitCount = 0;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getPatientName() {
        return patientName;
    }

    public void setPatientName(String patientName) {
        this.patientName = patientName;
    }

    public String getPatientPhone() {
        return patientPhone;
    }

    public void setPatientPhone(String patientPhone) {
        this.patientPhone = patientPhone;
    }

    public String getPatientIdNumber() {
        return patientIdNumber;
    }

    public void setPatientIdNumber(String patientIdNumber) {
        this.patientIdNumber = patientIdNumber;
    }

    public String getPatientStatus() {
        return patientStatus;
    }

    public void setPatientStatus(String patientStatus) {
        this.patientStatus = patientStatus;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Integer getAppointmentCount() {
        return appointmentCount;
    }

    public void setAppointmentCount(Integer appointmentCount) {
        this.appointmentCount = appointmentCount;
    }

    public Integer getVisitCount() {
        return visitCount;
    }

    public void setVisitCount(Integer visitCount) {
        this.visitCount = visitCount;
    }
}
