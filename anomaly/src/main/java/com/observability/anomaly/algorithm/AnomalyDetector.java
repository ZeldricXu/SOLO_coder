package com.observability.anomaly.algorithm;

import java.util.List;
import java.util.Map;

public interface AnomalyDetector {

    String getName();

    AnomalyResult detect(List<Double> historicalData, double currentValue, Map<String, Object> params);

    default boolean supports(String algorithm) {
        return getName().equalsIgnoreCase(algorithm);
    }
}
