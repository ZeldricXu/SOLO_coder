package com.device.platform.dto;

import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class MetricsResponse {
    private String snapshotId;
    private Instant timestamp;
    private Map<String, Object> metrics;
    private Map<String, String> dimensions;
}
