package com.parking.platform.common.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class RunEntity extends BaseEntity {

    private String entityId;
    private String phase;
    private Double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;

    public RunEntity() {
        super();
        this.phase = "initializing";
        this.progress = 0.0;
        this.startedAt = Instant.now();
    }

    @Override
    protected String getIdPrefix() {
        return "run";
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
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

    public boolean isCompleted() {
        return completedAt != null;
    }

    public boolean isFailed() {
        return errorDetail != null;
    }

    public void complete() {
        this.phase = "completed";
        this.progress = 1.0;
        this.completedAt = Instant.now();
    }

    public void fail(String errorDetail) {
        this.phase = "failed";
        this.errorDetail = errorDetail;
        this.completedAt = Instant.now();
    }

    public void updateProgress(String phase, double progress) {
        this.phase = phase;
        this.progress = progress;
        this.touch();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("run_id", getId());
        map.put("entity_id", entityId);
        map.put("phase", phase);
        map.put("progress", progress);
        map.put("started_at", startedAt);
        map.put("completed_at", completedAt);
        map.put("error_detail", errorDetail);
        return map;
    }
}
