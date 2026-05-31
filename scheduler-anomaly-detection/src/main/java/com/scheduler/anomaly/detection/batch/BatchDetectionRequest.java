package com.scheduler.anomaly.detection.batch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchDetectionRequest {
    private String namespace;
    private String metricName;
    private List<Double> historicalValues;
    private List<Long> historicalTimestamps;
    private double currentValue;
    private Instant currentTimestamp;
    private Map<String, String> dimensions;
    private List<String> algorithms;
    private int historyHours;
}
