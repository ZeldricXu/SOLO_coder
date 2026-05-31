package com.scheduler.anomaly.detection.batch;

import com.scheduler.anomaly.detection.AnomalyResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchDetectionResult {
    private String batchId;
    private String namespace;
    private int totalRequests;
    private int anomalyCount;
    private List<AnomalyResult> results;
    private Map<String, Integer> algorithmStats;
    private long processingTimeMs;
    private String error;
    private boolean success;
}
