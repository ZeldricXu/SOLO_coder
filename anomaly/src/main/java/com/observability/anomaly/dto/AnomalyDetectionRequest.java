package com.observability.anomaly.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class AnomalyDetectionRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String metricName;
    private double value;
    private String algorithm;
    private Map<String, Object> params;
}
