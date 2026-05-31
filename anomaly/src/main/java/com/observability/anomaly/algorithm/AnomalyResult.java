package com.observability.anomaly.algorithm;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class AnomalyResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean anomaly;
    private String algorithm;
    private double currentValue;
    private double baseline;
    private double threshold;
    private double deviation;
    private String severity;
    private Map<String, Object> details = new java.util.HashMap<>();

    public static AnomalyResult normal(String algorithm, double currentValue, double baseline) {
        AnomalyResult result = new AnomalyResult();
        result.setAnomaly(false);
        result.setAlgorithm(algorithm);
        result.setCurrentValue(currentValue);
        result.setBaseline(baseline);
        result.setSeverity("normal");
        return result;
    }

    public static AnomalyResult anomaly(String algorithm, double currentValue, double baseline,
                                        double threshold, double deviation, String severity) {
        AnomalyResult result = new AnomalyResult();
        result.setAnomaly(true);
        result.setAlgorithm(algorithm);
        result.setCurrentValue(currentValue);
        result.setBaseline(baseline);
        result.setThreshold(threshold);
        result.setDeviation(deviation);
        result.setSeverity(severity);
        return result;
    }
}
