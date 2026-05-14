package com.projmanage.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class RiskDetectionTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String projectId;
    private String taskName;
    private String taskAssignee;
    private String taskStatus;
    private String taskPriority;
    private LocalDate dueDate;
    private Integer progress;
    private String detectionType;
    private LocalDateTime submittedAt;
    private String status;

    public RiskDetectionTask() {
    }

    public RiskDetectionTask(com.projmanage.model.Task task) {
        this.taskId = task.getTaskId();
        this.projectId = task.getProjectId();
        this.taskName = task.getTaskName();
        this.taskAssignee = task.getTaskAssignee();
        this.taskStatus = task.getTaskStatus();
        this.taskPriority = task.getTaskPriority();
        this.dueDate = task.getDueDate();
        this.progress = task.getProgress();
        this.detectionType = "auto";
        this.submittedAt = LocalDateTime.now();
        this.status = "pending";
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getTaskAssignee() {
        return taskAssignee;
    }

    public void setTaskAssignee(String taskAssignee) {
        this.taskAssignee = taskAssignee;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getTaskPriority() {
        return taskPriority;
    }

    public void setTaskPriority(String taskPriority) {
        this.taskPriority = taskPriority;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public String getDetectionType() {
        return detectionType;
    }

    public void setDetectionType(String detectionType) {
        this.detectionType = detectionType;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public com.projmanage.model.Task toTaskModel() {
        com.projmanage.model.Task task = new com.projmanage.model.Task();
        task.setTaskId(this.taskId);
        task.setProjectId(this.projectId);
        task.setTaskName(this.taskName);
        task.setTaskAssignee(this.taskAssignee);
        task.setTaskStatus(this.taskStatus);
        task.setTaskPriority(this.taskPriority);
        task.setDueDate(this.dueDate);
        task.setProgress(this.progress);
        return task;
    }
}
