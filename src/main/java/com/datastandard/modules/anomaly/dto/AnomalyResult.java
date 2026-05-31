package com.datastandard.modules.anomaly.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyResult {

    private String resultId;
    private String detectionCode;
    private String metricCode;
    private Long entityId;
    private Long instanceId;
    private boolean isAnomaly;
    private String anomalyType;
    private String severity;
    private BigDecimal confidence;
    private BigDecimal anomalyScore;
    private BigDecimal threshold;
    private BigDecimal expectedValue;
    private BigDecimal actualValue;
    private LocalDateTime detectedAt;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private String algorithmType;
    private Map<String, Object> anomalyData;
    private Map<String, Object> analysisResult;
    private List<String> affectedDimensions;
    private String description;
    private String suggestedAction;
}
