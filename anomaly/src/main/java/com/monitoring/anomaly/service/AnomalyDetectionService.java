package com.monitoring.anomaly.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.monitoring.anomaly.algorithm.AnomalyDetector;
import com.monitoring.anomaly.cache.EvictingCircularBuffer;
import com.monitoring.common.model.MetricsSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    private static final int DEFAULT_HISTORY_CAPACITY = 2048;
    private static final int CACHE_MAX_SIZE = 10000;
    private static final Duration CACHE_EXPIRE_AFTER_WRITE = Duration.ofHours(24);

    private final Map<String, AnomalyDetector> detectors = new ConcurrentHashMap<>();

    private final Cache<String, EvictingCircularBuffer> historyCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_EXPIRE_AFTER_WRITE)
            .maximumSize(CACHE_MAX_SIZE)
            .build();

    public void registerDetector(AnomalyDetector detector) {
        detectors.put(detector.getName(), detector);
    }

    public Mono<AnomalyDetector.AnomalyResult> detect(String metricName, double currentValue, String algorithm,
                                                      double sensitivity, int windowSize,
                                                      double minThreshold, double maxThreshold) {
        return Mono.fromSupplier(() -> {
            AnomalyDetector detector = getDetector(algorithm);
            if (detector == null) {
                return createNoopResult("No detector available for algorithm: " + algorithm);
            }

            EvictingCircularBuffer buffer = getOrCreateBuffer(metricName, windowSize);
            AnomalyDetector.AnomalyConfig config = createConfig(sensitivity, windowSize, minThreshold, maxThreshold);

            double[] recentValues = buffer.getRecentValues(windowSize);
            java.util.List<Double> historicalData = toDoubleList(recentValues);

            AnomalyDetector.AnomalyResult result = detector.detect(historicalData, currentValue, config);
            updateHistory(buffer, currentValue, windowSize);

            logAnomalyIfNeeded(metricName, result);

            return result;
        });
    }

    public Mono<Map<String, AnomalyDetector.AnomalyResult>> detectAll(String metricName, double currentValue,
                                                                       double sensitivity, int windowSize,
                                                                       double minThreshold, double maxThreshold) {
        return Mono.fromSupplier(() -> {
            Map<String, AnomalyDetector.AnomalyResult> results = new HashMap<>(detectors.size());
            AnomalyDetector.AnomalyConfig config = createConfig(sensitivity, windowSize, minThreshold, maxThreshold);

            EvictingCircularBuffer buffer = getOrCreateBuffer(metricName, windowSize);
            double[] recentValues = buffer.getRecentValues(windowSize);
            java.util.List<Double> historicalData = toDoubleList(recentValues);

            for (AnomalyDetector detector : detectors.values()) {
                results.put(detector.getName(), detector.detect(historicalData, currentValue, config));
            }

            updateHistory(buffer, currentValue, windowSize);

            return results;
        });
    }

    public Mono<AnomalyDetector.AnomalyResult> detectFromSnapshot(MetricsSnapshot snapshot, String algorithm,
                                                                  double sensitivity, int windowSize,
                                                                  double minThreshold, double maxThreshold) {
        double value = calculateSnapshotValue(snapshot);
        String metricKey = buildMetricKey(snapshot);
        return detect(metricKey, value, algorithm, sensitivity, windowSize, minThreshold, maxThreshold);
    }

    public Set<String> getAvailableAlgorithms() {
        return detectors.keySet();
    }

    public void trainModel(String metricName, java.util.List<Double> trainingData) {
        EvictingCircularBuffer buffer = new EvictingCircularBuffer(DEFAULT_HISTORY_CAPACITY);
        buffer.fillFrom(trainingData);
        historyCache.put(metricName, buffer);
        log.info("Trained anomaly detection model for metric {} with {} data points", metricName, trainingData.size());
    }

    public void clearHistory(String metricName) {
        historyCache.invalidate(metricName);
        log.info("Cleared anomaly detection history for metric {}", metricName);
    }

    private AnomalyDetector getDetector(String algorithm) {
        AnomalyDetector detector = detectors.get(algorithm);
        return detector != null ? detector : detectors.get("zscore");
    }

    private EvictingCircularBuffer getOrCreateBuffer(String metricName, int windowSize) {
        return historyCache.get(metricName, key -> new EvictingCircularBuffer(Math.max(windowSize * 2, DEFAULT_HISTORY_CAPACITY)));
    }

    private static AnomalyDetector.AnomalyConfig createConfig(double sensitivity, int windowSize, double minThreshold, double maxThreshold) {
        return new AnomalyDetector.AnomalyConfig(sensitivity, windowSize, minThreshold, maxThreshold);
    }

    private static AnomalyDetector.AnomalyResult createNoopResult(String message) {
        return new AnomalyDetector.AnomalyResult(false, 0, 0, "none", message, 0, 0);
    }

    private static void updateHistory(EvictingCircularBuffer buffer, double currentValue, int windowSize) {
        buffer.add(currentValue);
    }

    private static void logAnomalyIfNeeded(String metricName, AnomalyDetector.AnomalyResult result) {
        if (result.isAnomaly()) {
            log.warn("Anomaly detected for metric {}: {}", metricName, result.message());
        }
    }

    private static double calculateSnapshotValue(MetricsSnapshot snapshot) {
        return snapshot.getMetrics().values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static String buildMetricKey(MetricsSnapshot snapshot) {
        return String.join(":", snapshot.getDimensions().values());
    }

    private static java.util.List<Double> toDoubleList(double[] values) {
        java.util.ArrayList<Double> list = new java.util.ArrayList<>(values.length);
        for (double value : values) {
            list.add(value);
        }
        return list;
    }
}
