package com.device.platform.dto;

import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class AggregationResultResponse {
    private String windowId;
    private String deviceId;
    private String metricName;
    private Long windowStartMs;
    private Long windowEndMs;
    private Long windowSizeMs;
    private Long recordCount;
    private Double minValue;
    private Double maxValue;
    private Double avgValue;
    private Double sumValue;
    private Double variance;
    private Double stdDev;
    private Map<Double, Double> percentiles;
    private Instant aggregatedAt;
}
