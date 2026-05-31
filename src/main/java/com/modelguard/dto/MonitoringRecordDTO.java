package com.modelguard.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class MonitoringRecordDTO {

    private String modelId;

    private String version;

    private LocalDateTime timestamp;

    private Map<String, Object> metrics;

    private Map<String, Object> predictionDistribution;

    private Map<String, Object> featureDistribution;

    private Long requestCount;

    private Long successCount;

    private Long errorCount;

    private Double avgLatencyMs;

    private Double p50LatencyMs;

    private Double p95LatencyMs;

    private Double p99LatencyMs;

    private Double throughput;

    private Double errorRate;

    private String timeWindow;

    private Map<String, Object> alerts;
}
