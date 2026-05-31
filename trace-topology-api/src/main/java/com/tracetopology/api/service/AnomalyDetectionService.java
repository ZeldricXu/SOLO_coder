package com.tracetopology.api.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface AnomalyDetectionService {

    List<Map<String, Object>> detectAnomalies(String metricName, Map<String, String> dimensions,
                                               Instant startTime, Instant endTime, String algorithm);

    Map<String, Object> detectAnomaly(String metricName, double currentValue, Map<String, String> dimensions,
                                       String algorithm);

    void trainModel(String metricName, Map<String, String> dimensions, List<Map<String, Object>> historicalData);

    Map<String, Object> getBaseline(String metricName, Map<String, String> dimensions, String algorithm);

    List<String> getSupportedAlgorithms();

    void configureAlgorithm(String algorithmName, Map<String, Object> parameters);
}
