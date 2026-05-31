package com.tracetopology.domain.entity;

import com.tracetopology.common.utils.IdGenerator;
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

    private String runId;
    private String entityId;
    private String phase;
    private double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;

    public static RunInstance create(String entityId) {
        return RunInstance.builder()
                .runId(IdGenerator.generateId("run"))
                .entityId(entityId)
                .phase("pending")
                .progress(0.0)
                .startedAt(Instant.now())
                .build();
    }

    public void updateProgress(String phase, double progress) {
        this.phase = phase;
        this.progress = progress;
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

    public boolean isFinished() {
        return "completed".equals(phase) || "failed".equals(phase);
    }
}
