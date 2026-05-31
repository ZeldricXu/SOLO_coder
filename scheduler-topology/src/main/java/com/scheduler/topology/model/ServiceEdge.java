package com.scheduler.topology.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceEdge {
    private String sourceService;
    private String targetService;
    private String operation;
    private long callCount;
    private long errorCount;
    private double avgLatencyMs;
    private double p95LatencyMs;
    private Instant lastCallTime;
    private String protocol;
}
