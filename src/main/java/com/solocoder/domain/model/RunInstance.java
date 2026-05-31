package com.solocoder.domain.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunInstance {
    private String runId;
    private String entityId;
    private String phase;
    private Double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;
}
