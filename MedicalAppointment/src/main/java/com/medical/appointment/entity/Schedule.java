package com.medical.appointment.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "schedules")
public class Schedule {
    
    @Id
    @Column(name = "schedule_id")
    private String scheduleId;
    
    @Column(name = "department_id", nullable = false)
    private String departmentId;
    
    @Column(name = "doctor_id", nullable = false)
    private String doctorId;
    
    @Column(name = "schedule_date")
    private LocalDate scheduleDate;
    
    @Column(name = "schedule_time")
    private String scheduleTime;
    
    @Column(name = "schedule_quota")
    private Integer scheduleQuota;
    
    @Column(name = "schedule_available")
    private Integer scheduleAvailable;
    
    @Column(name = "schedule_status")
    private String scheduleStatus;

    public Schedule() {}

    public String getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }

    public String getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }

    public String getDoctorId() {
        return doctorId;
    }

    public void setDoctorId(String doctorId) {
        this.doctorId = doctorId;
    }

    public LocalDate getScheduleDate() {
        return scheduleDate;
    }

    public void setScheduleDate(LocalDate scheduleDate) {
        this.scheduleDate = scheduleDate;
    }

    public String getScheduleTime() {
        return scheduleTime;
    }

    public void setScheduleTime(String scheduleTime) {
        this.scheduleTime = scheduleTime;
    }

    public Integer getScheduleQuota() {
        return scheduleQuota;
    }

    public void setScheduleQuota(Integer scheduleQuota) {
        this.scheduleQuota = scheduleQuota;
    }

    public Integer getScheduleAvailable() {
        return scheduleAvailable;
    }

    public void setScheduleAvailable(Integer scheduleAvailable) {
        this.scheduleAvailable = scheduleAvailable;
    }

    public String getScheduleStatus() {
        return scheduleStatus;
    }

    public void setScheduleStatus(String scheduleStatus) {
        this.scheduleStatus = scheduleStatus;
    }
}
