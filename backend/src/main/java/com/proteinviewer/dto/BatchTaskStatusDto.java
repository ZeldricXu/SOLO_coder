package com.proteinviewer.dto;

public class BatchTaskStatusDto {
    private String taskId;
    private String status;
    private String taskType;
    private int totalCount;
    private int completedCount;
    private double progress;
    private int queuePosition;
    private int queueSize;
    private double estimatedWaitSeconds;
    private double estimatedRemainingSeconds;
    private String resultUrl;
    private String errorMessage;
    private java.time.Instant createdAt;
    private java.time.Instant updatedAt;
    private java.time.Instant startedAt;
    private Long submittedBy;

    public BatchTaskStatusDto() {}

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getCompletedCount() { return completedCount; }
    public void setCompletedCount(int completedCount) { this.completedCount = completedCount; }
    public double getProgress() { return progress; }
    public void setProgress(double progress) { this.progress = progress; }
    public int getQueuePosition() { return queuePosition; }
    public void setQueuePosition(int queuePosition) { this.queuePosition = queuePosition; }
    public int getQueueSize() { return queueSize; }
    public void setQueueSize(int queueSize) { this.queueSize = queueSize; }
    public double getEstimatedWaitSeconds() { return estimatedWaitSeconds; }
    public void setEstimatedWaitSeconds(double estimatedWaitSeconds) { this.estimatedWaitSeconds = estimatedWaitSeconds; }
    public double getEstimatedRemainingSeconds() { return estimatedRemainingSeconds; }
    public void setEstimatedRemainingSeconds(double estimatedRemainingSeconds) { this.estimatedRemainingSeconds = estimatedRemainingSeconds; }
    public String getResultUrl() { return resultUrl; }
    public void setResultUrl(String resultUrl) { this.resultUrl = resultUrl; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
    public java.time.Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.Instant updatedAt) { this.updatedAt = updatedAt; }
    public java.time.Instant getStartedAt() { return startedAt; }
    public void setStartedAt(java.time.Instant startedAt) { this.startedAt = startedAt; }
    public Long getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(Long submittedBy) { this.submittedBy = submittedBy; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final BatchTaskStatusDto dto = new BatchTaskStatusDto();
        public Builder taskId(String v) { dto.taskId = v; return this; }
        public Builder status(String v) { dto.status = v; return this; }
        public Builder taskType(String v) { dto.taskType = v; return this; }
        public Builder totalCount(int v) { dto.totalCount = v; return this; }
        public Builder completedCount(int v) { dto.completedCount = v; return this; }
        public Builder progress(double v) { dto.progress = v; return this; }
        public Builder queuePosition(int v) { dto.queuePosition = v; return this; }
        public Builder queueSize(int v) { dto.queueSize = v; return this; }
        public Builder estimatedWaitSeconds(double v) { dto.estimatedWaitSeconds = v; return this; }
        public Builder estimatedRemainingSeconds(double v) { dto.estimatedRemainingSeconds = v; return this; }
        public Builder resultUrl(String v) { dto.resultUrl = v; return this; }
        public Builder errorMessage(String v) { dto.errorMessage = v; return this; }
        public Builder createdAt(java.time.Instant v) { dto.createdAt = v; return this; }
        public Builder updatedAt(java.time.Instant v) { dto.updatedAt = v; return this; }
        public Builder startedAt(java.time.Instant v) { dto.startedAt = v; return this; }
        public Builder submittedBy(Long v) { dto.submittedBy = v; return this; }
        public BatchTaskStatusDto build() { return dto; }
    }
}
