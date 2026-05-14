package com.projectcollab.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

public class DocumentShareTask implements Serializable {

    private String taskId;
    private String documentId;
    private String documentName;
    private String projectId;
    private String uploaderId;
    private LocalDateTime createdAt;
    private int retryCount;
    private String status;

    public DocumentShareTask() {
    }

    public DocumentShareTask(String documentId, String documentName, String projectId, String uploaderId) {
        this.taskId = UUID.randomUUID().toString();
        this.documentId = documentId;
        this.documentName = documentName;
        this.projectId = projectId;
        this.uploaderId = uploaderId;
        this.createdAt = LocalDateTime.now();
        this.retryCount = 0;
        this.status = "PENDING";
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(String uploaderId) {
        this.uploaderId = uploaderId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
