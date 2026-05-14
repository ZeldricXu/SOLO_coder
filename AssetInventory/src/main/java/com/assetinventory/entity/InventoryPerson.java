package com.assetinventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "inventory_persons")
public class InventoryPerson {

    @Id
    @Column(name = "person_id", nullable = false, length = 50)
    private String personId;

    @Column(name = "person_name", nullable = false, length = 100)
    private String personName;

    @Column(name = "person_department", nullable = false, length = 100)
    private String personDepartment;

    @Column(name = "person_status", nullable = false, length = 50)
    private String personStatus;

    @Column(name = "task_count", nullable = false)
    private int taskCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public InventoryPerson() {
    }

    public String getPersonId() {
        return personId;
    }

    public void setPersonId(String personId) {
        this.personId = personId;
    }

    public String getPersonName() {
        return personName;
    }

    public void setPersonName(String personName) {
        this.personName = personName;
    }

    public String getPersonDepartment() {
        return personDepartment;
    }

    public void setPersonDepartment(String personDepartment) {
        this.personDepartment = personDepartment;
    }

    public String getPersonStatus() {
        return personStatus;
    }

    public void setPersonStatus(String personStatus) {
        this.personStatus = personStatus;
    }

    public int getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(int taskCount) {
        this.taskCount = taskCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
