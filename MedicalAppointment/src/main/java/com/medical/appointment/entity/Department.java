package com.medical.appointment.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "departments")
public class Department {
    
    @Id
    @Column(name = "department_id")
    private String departmentId;
    
    @Column(name = "hospital_id", nullable = false)
    private String hospitalId;
    
    @Column(name = "department_name", nullable = false)
    private String departmentName;
    
    @Column(name = "department_type")
    private String departmentType;
    
    @Column(name = "department_status")
    private String departmentStatus;

    public Department() {}

    public String getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }

    public String getHospitalId() {
        return hospitalId;
    }

    public void setHospitalId(String hospitalId) {
        this.hospitalId = hospitalId;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }

    public String getDepartmentType() {
        return departmentType;
    }

    public void setDepartmentType(String departmentType) {
        this.departmentType = departmentType;
    }

    public String getDepartmentStatus() {
        return departmentStatus;
    }

    public void setDepartmentStatus(String departmentStatus) {
        this.departmentStatus = departmentStatus;
    }
}
