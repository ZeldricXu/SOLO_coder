package com.medical.appointment.entity;

import jakarta.persistence.*;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "statistics")
public class Statistics {
    
    @Id
    @Column(name = "stat_id")
    private String statId;
    
    @Column(name = "stat_month")
    private String statMonth;
    
    @Column(name = "appointment_count")
    private Integer appointmentCount;
    
    @Column(name = "visit_count")
    private Integer visitCount;
    
    @Column(name = "cancel_count")
    private Integer cancelCount;
    
    @Lob
    @Column(name = "department_stat", columnDefinition = "TEXT")
    private String departmentStatJson;
    
    @Transient
    private Map<String, Integer> departmentStat;

    public Statistics() {
        this.appointmentCount = 0;
        this.visitCount = 0;
        this.cancelCount = 0;
        this.departmentStat = new HashMap<>();
    }

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
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

    public Integer getCancelCount() {
        return cancelCount;
    }

    public void setCancelCount(Integer cancelCount) {
        this.cancelCount = cancelCount;
    }

    public String getDepartmentStatJson() {
        return departmentStatJson;
    }

    public void setDepartmentStatJson(String departmentStatJson) {
        this.departmentStatJson = departmentStatJson;
    }

    public Map<String, Integer> getDepartmentStat() {
        return departmentStat;
    }

    public void setDepartmentStat(Map<String, Integer> departmentStat) {
        this.departmentStat = departmentStat;
    }
}
