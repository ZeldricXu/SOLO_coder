package com.taskplatform.common.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class MetricsSnapshot {

    private String snapshotId;
    private LocalDateTime timestamp;
    private Map<String, Double> metrics;
    private Map<String, String> dimensions;

    public double getThroughput() {
        return metrics != null && metrics.containsKey("throughput")
                ? metrics.get("throughput") : 0.0;
    }

    public double getLatencyP99() {
        return metrics != null && metrics.containsKey("latency_p99")
                ? metrics.get("latency_p99") : 0.0;
    }

    public double getErrorRate() {
        return metrics != null && metrics.containsKey("error_rate")
                ? metrics.get("error_rate") : 0.0;
    }
}
