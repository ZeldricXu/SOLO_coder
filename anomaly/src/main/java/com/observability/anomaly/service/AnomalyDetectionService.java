package com.observability.anomaly.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.observability.anomaly.algorithm.AnomalyDetector;
import com.observability.anomaly.algorithm.AnomalyResult;
import com.observability.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    private final List<AnomalyDetector> detectors;

    private final Cache<String, List<Double>> historyCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();

    public Mono<AnomalyResult> detect(String metricName, double currentValue,
                                       String algorithm, Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            List<Double> history = historyCache.get(metricName, k -> new ArrayList<>());
            AnomalyDetector detector = findDetector(algorithm);
            AnomalyResult result = detector.detect(history, currentValue, params);

            updateHistory(metricName, currentValue, history);

            if (result.isAnomaly()) {
                log.warn("Anomaly detected - metric: {}, algorithm: {}, value: {}, baseline: {}, severity: {}",
                        metricName, algorithm, currentValue, result.getBaseline(), result.getSeverity());
            }

            return result;
        });
    }

    public Mono<Map<String, AnomalyResult>> detectAll(String metricName, double currentValue,
                                                        Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            List<Double> history = historyCache.get(metricName, k -> new ArrayList<>());
            Map<String, AnomalyResult> results = new HashMap<>();

            for (AnomalyDetector detector : detectors) {
                try {
                    AnomalyResult result = detector.detect(history, currentValue, params);
                    results.put(detector.getName(), result);
                } catch (Exception e) {
                    log.error("Detector {} failed for metric: {}", detector.getName(), metricName, e);
                }
            }

            updateHistory(metricName, currentValue, history);
            return results;
        });
    }

    public Mono<List<String>> getAvailableAlgorithms() {
        return Mono.fromCallable(() ->
                detectors.stream().map(AnomalyDetector::getName).toList());
    }

    public Mono<Void> addHistoryData(String metricName, double value) {
        return Mono.fromRunnable(() -> {
            List<Double> history = historyCache.get(metricName, k -> new ArrayList<>());
            updateHistory(metricName, value, history);
        });
    }

    public Mono<List<Double>> getHistory(String metricName) {
        return Mono.fromCallable(() ->
                historyCache.getOrDefault(metricName, Collections.emptyList()));
    }

    private AnomalyDetector findDetector(String algorithm) {
        return detectors.stream()
                .filter(d -> d.supports(algorithm))
                .findFirst()
                .orElseThrow(() -> BusinessException.validationError("Unknown algorithm: " + algorithm));
    }

    private void updateHistory(String metricName, double currentValue, List<Double> history) {
        history.add(currentValue);
        if (history.size() > 1000) {
            history.remove(0);
        }
        historyCache.put(metricName, history);
    }
}
