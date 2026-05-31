package com.parking.platform.core.entity;

import com.parking.platform.common.constant.Constants;
import com.parking.platform.common.entity.BaseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Task extends BaseEntity {

    private String type;
    private String status = Constants.STATUS_PENDING;
    private String phase = Constants.PHASE_INITIALIZING;
    private Double progress = 0.0;
    private Map<String, Object> payload;
    private Map<String, Object> config;
    private Map<String, Object> result;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;
    private Integer retryCount = 0;
    private Integer maxRetries = 3;
    private Long timeout = 300000L;
    private String priority = "NORMAL";
    private String createdBy;

    public Task() {
        super();
        this.payload = new HashMap<>();
        this.config = new HashMap<>();
    }

    @Override
    protected String getIdPrefix() {
        return "task";
    }

    public void start() {
        this.status = Constants.STATUS_RUNNING;
        this.phase = Constants.PHASE_RUNNING;
        this.startedAt = Instant.now();
        this.touch();
    }

    public void updateProgress(String phase, double progress) {
        this.phase = phase;
        this.progress = progress;
        this.touch();
    }

    public void complete(Map<String, Object> result) {
        this.status = Constants.STATUS_COMPLETED;
        this.phase = Constants.PHASE_COMPLETED;
        this.progress = 1.0;
        this.result = result;
        this.completedAt = Instant.now();
        this.touch();
    }

    public void fail(String errorDetail) {
        this.status = Constants.STATUS_FAILED;
        this.phase = Constants.PHASE_FAILED;
        this.errorDetail = errorDetail;
        this.completedAt = Instant.now();
        this.touch();
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public void incrementRetry() {
        this.retryCount++;
        this.touch();
    }

    public boolean isCompleted() {
        return Constants.STATUS_COMPLETED.equals(status) || Constants.STATUS_FAILED.equals(status);
    }

    public boolean isRunning() {
        return Constants.STATUS_RUNNING.equals(status);
    }

    public boolean isPending() {
        return Constants.STATUS_PENDING.equals(status);
    }

    public boolean isExpired() {
        if (startedAt == null || timeout == null) {
            return false;
        }
        return Instant.now().isAfter(startedAt.plusMillis(timeout));
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("type", type);
        map.put("status", status);
        map.put("phase", phase);
        map.put("progress", progress);
        map.put("payload", payload);
        map.put("config", config);
        map.put("result", result);
        map.put("startedAt", startedAt);
        map.put("completedAt", completedAt);
        map.put("errorDetail", errorDetail);
        map.put("retryCount", retryCount);
        map.put("maxRetries", maxRetries);
        map.put("timeout", timeout);
        map.put("priority", priority);
        map.put("createdBy", createdBy);
        return map;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public Double getProgress() {
        return progress;
    }

    public void setProgress(Double progress) {
        this.progress = progress;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
        this.result = result;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public void setErrorDetail(String errorDetail) {
        this.errorDetail = errorDetail;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Long getTimeout() {
        return timeout;
    }

    public void setTimeout(Long timeout) {
        this.timeout = timeout;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
