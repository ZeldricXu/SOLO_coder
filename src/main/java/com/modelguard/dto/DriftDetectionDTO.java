package com.modelguard.dto;

import lombok.Data;
import java.util.Map;

@Data
public class DriftDetectionDTO {

    private String modelId;

    private String version;

    private String driftType;

    private String featureName;

    private Double threshold;

    private Map<String, Object> baselineDistribution;

    private Map<String, Object> currentDistribution;

    private String timeWindow;

    private Map<String, Object> metadata;
}
