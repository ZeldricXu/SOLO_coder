package com.medical.appointment.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "doctors")
public class Doctor {
    
    @Id
    @Column(name = "doctor_id")
    private String doctorId;
    
    @Column(name = "doctor_name", nullable = false)
    private String doctorName;
    
    @Column(name = "doctor_title")
    private String doctorTitle;
    
    @Column(name = "department_id", nullable = false)
    private String departmentId;
    
    @Column(name = "doctor_rating")
    private Double doctorRating;
    
    @Column(name = "doctor_status")
    private String doctorStatus;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "appointment_count")
    private Integer appointmentCount;
    
    @Column(name = "visit_count")
    private Integer visitCount;

    public Doctor() {
        this.appointmentCount = 0;
        this.visitCount = 0;
        this.doctorRating = 0.0;
    }

    public String getDoctorId() {
        return doctorId;
    }

    public void setDoctorId(String doctorId) {
        this.doctorId = doctorId;
    }

    public String getDoctorName() {
        return doctorName;
    }

    public void setDoctorName(String doctorName) {
        this.doctorName = doctorName;
    }

    public String getDoctorTitle() {
        return doctorTitle;
    }

    public void setDoctorTitle(String doctorTitle) {
        this.doctorTitle = doctorTitle;
    }

    public String getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }

    public Double getDoctorRating() {
        return doctorRating;
    }

    public void setDoctorRating(Double doctorRating) {
        this.doctorRating = doctorRating;
    }

    public String getDoctorStatus() {
        return doctorStatus;
    }

    public void setDoctorStatus(String doctorStatus) {
        this.doctorStatus = doctorStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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
