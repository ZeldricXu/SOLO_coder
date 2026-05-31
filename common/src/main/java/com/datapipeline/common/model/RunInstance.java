package com.datapipeline.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunInstance {

    public enum Phase {
        INITIALIZING,
        RUNNING,
        COMPLETED,
        FAILED,
        TIMED_OUT,
        CANCELLED
    }

    private String runId;
    private String entityId;
    private Phase phase;
    private double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;

    public RunInstance markRunning() {
        this.phase = Phase.RUNNING;
        return this;
    }

    public RunInstance markCompleted() {
        this.phase = Phase.COMPLETED;
        this.progress = 1.0;
        this.completedAt = Instant.now();
        return this;
    }

    public RunInstance markFailed(String error) {
        this.phase = Phase.FAILED;
        this.errorDetail = error;
        this.completedAt = Instant.now();
        return this;
    }

    public RunInstance markTimeout(String error) {
        this.phase = Phase.TIMED_OUT;
        this.errorDetail = error;
        this.completedAt = Instant.now();
        return this;
    }

    public RunInstance updateProgress(double progress) {
        if (progress < 0.0) progress = 0.0;
        if (progress > 1.0) progress = 1.0;
        this.progress = progress;
        return this;
    }

}
