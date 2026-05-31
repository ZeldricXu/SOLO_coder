package com.tracetopology.domain.topology;

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

    private String id;
    private String sourceServiceId;
    private String targetServiceId;
    private String sourceServiceName;
    private String targetServiceName;
    private long callCount;
    private long errorCount;
    private double avgLatencyMs;
    private double p99LatencyMs;
    private Instant firstCallAt;
    private Instant lastCallAt;

    public void recordCall(long latencyMs, boolean success) {
        this.callCount++;
        if (!success) {
            this.errorCount++;
        }
        updateLatency(latencyMs);
        this.lastCallAt = Instant.now();
    }

    private void updateLatency(long latencyMs) {
        this.avgLatencyMs = (this.avgLatencyMs * (this.callCount - 1) + latencyMs) / this.callCount;
        this.p99LatencyMs = Math.max(this.p99LatencyMs, latencyMs);
    }
}
