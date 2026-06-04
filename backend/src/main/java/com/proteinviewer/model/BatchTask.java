package com.proteinviewer.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "batch_tasks")
public class BatchTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 36)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TaskType taskType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String inputJson;

    @Column(columnDefinition = "TEXT")
    private String resultJson;

    @Column(length = 64)
    private String workerId;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    private int priority;

    private int retryCount;

    private String resultStorageKey;

    private int totalCount;

    private int completedCount;

    @Column(length = 1024)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    private Long submittedBy;

    private double estimatedDurationSeconds;

    @Transient
    private transient Runnable taskRunnable;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    public BatchTask() {}

    public BatchTask(String taskId, TaskType taskType, String status, int totalCount, long submittedBy) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.status = status;
        this.totalCount = totalCount;
        this.submittedBy = submittedBy;
        this.estimatedDurationSeconds = estimateDuration(taskType, totalCount);
    }

    public BatchTask(String taskId, String status, int totalCount) {
        this(taskId, TaskType.BATCH_ANALYSIS, status, totalCount, 1L);
    }

    private static double estimateDuration(TaskType type, int count) {
        switch (type) {
            case ELECTROSTATIC_SURFACE:
                return count * 15.0;
            case MULTI_STRUCTURE_ALIGNMENT:
                return count * count * 0.5;
            case BATCH_ANALYSIS:
                return count * 3.0;
            default:
                return count * 2.0;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public TaskType getTaskType() { return taskType; }
    public void setTaskType(TaskType taskType) { this.taskType = taskType; }
    public String getStatus() { return status; }
    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
        if ("RUNNING".equals(status) && startedAt == null) {
            this.startedAt = Instant.now();
        }
        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            this.completedAt = Instant.now();
        }
    }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(Instant heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getResultStorageKey() { return resultStorageKey; }
    public void setResultStorageKey(String key) { this.resultStorageKey = key; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int count) { this.totalCount = count; }
    public int getCompletedCount() { return completedCount; }
    public void setCompletedCount(int count) {
        this.completedCount = count;
        this.updatedAt = Instant.now();
    }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(Long user) { this.submittedBy = user; }
    public double getEstimatedDurationSeconds() { return estimatedDurationSeconds; }
    public Runnable getTaskRunnable() { return taskRunnable; }
    public void setTaskRunnable(Runnable runnable) { this.taskRunnable = runnable; }

    public boolean isExpired(int retentionDays) {
        if (completedAt == null) return false;
        return completedAt.plus(java.time.Duration.ofDays(retentionDays)).isBefore(Instant.now());
    }
}
