package com.modelguard.dto;

import lombok.Data;
import java.util.Map;

@Data
public class EvaluationCreateDTO {

    private String evaluationId;

    private String modelId;

    private String version;

    private String evaluationType;

    private String datasetName;

    private String datasetVersion;

    private Map<String, Object> metrics;

    private Map<String, Object> metricDetails;

    private String baselineModelId;

    private String baselineVersion;

    private String evaluatedBy;

    private String notes;

    private Map<String, Object> metadata;
}
