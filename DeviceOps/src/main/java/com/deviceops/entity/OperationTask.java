package com.deviceops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "operation_tasks")
public class OperationTask {

    @Id
    @Column(name = "task_id")
    private String taskId;

    @Column(name = "fault_id", nullable = false)
    private String faultId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "operator_id")
    private String operatorId;

    @Column(name = "task_type", nullable = false)
    private String taskType;

    @Column(name = "task_status", nullable = false)
    private String taskStatus;

    @Column(name = "task_time", nullable = false)
    private LocalDateTime taskTime;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "result")
    private String result;

    @Column(name = "is_locked")
    private Boolean isLocked;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "lock_timeout_seconds")
    private Integer lockTimeoutSeconds;

    @Column(name = "priority_level")
    private String priorityLevel;

    public OperationTask() {
    }

    @PrePersist
    protected void onCreate() {
        taskTime = LocalDateTime.now();
        if (taskStatus == null) {
            taskStatus = "pending";
        }
        if (isLocked == null) {
            isLocked = false;
        }
        if (lockTimeoutSeconds == null) {
            lockTimeoutSeconds = determineLockTimeout(priorityLevel);
        }
    }

    private Integer determineLockTimeout(String priority) {
        if ("high".equals(priority)) {
            return 1800;
        } else if ("medium".equals(priority)) {
            return 3600;
        } else {
            return 7200;
        }
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getFaultId() {
        return faultId;
    }

    public void setFaultId(String faultId) {
        this.faultId = faultId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
    }

    public LocalDateTime getTaskTime() {
        return taskTime;
    }

    public void setTaskTime(LocalDateTime taskTime) {
        this.taskTime = taskTime;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Boolean getIsLocked() {
        return isLocked;
    }

    public void setIsLocked(Boolean locked) {
        isLocked = locked;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(LocalDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Integer getLockTimeoutSeconds() {
        return lockTimeoutSeconds;
    }

    public void setLockTimeoutSeconds(Integer lockTimeoutSeconds) {
        this.lockTimeoutSeconds = lockTimeoutSeconds;
    }

    public String getPriorityLevel() {
        return priorityLevel;
    }

    public void setPriorityLevel(String priorityLevel) {
        this.priorityLevel = priorityLevel;
    }
}
