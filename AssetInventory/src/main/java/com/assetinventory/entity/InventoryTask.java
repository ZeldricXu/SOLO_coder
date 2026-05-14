package com.assetinventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "inventory_tasks")
public class InventoryTask {

    @Id
    @Column(name = "task_id", nullable = false, length = 50)
    private String taskId;

    @Column(name = "plan_id", nullable = false, length = 50)
    private String planId;

    @Column(name = "task_range", nullable = false, length = 200)
    private String taskRange;

    @Column(name = "task_status", nullable = false, length = 50)
    private String taskStatus;

    @Column(name = "assigned_person", length = 50)
    private String assignedPerson;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "task_priority", length = 20)
    private String taskPriority;

    public InventoryTask() {
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getTaskRange() {
        return taskRange;
    }

    public void setTaskRange(String taskRange) {
        this.taskRange = taskRange;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getAssignedPerson() {
        return assignedPerson;
    }

    public void setAssignedPerson(String assignedPerson) {
        this.assignedPerson = assignedPerson;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getTaskPriority() {
        return taskPriority;
    }

    public void setTaskPriority(String taskPriority) {
        this.taskPriority = taskPriority;
    }
}
