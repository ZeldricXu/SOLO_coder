package com.scheduler.anomaly.detection.service;

import com.scheduler.anomaly.detection.AnomalyDetector;
import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.anomaly.detection.adapter.MetricsAdapter;
import com.scheduler.anomaly.detection.algorithm.AnomalyDetectionAlgorithm;
import com.scheduler.anomaly.detection.model.MetricSeries;
import com.scheduler.persistence.entity.MetricsSnapshot;
import com.scheduler.persistence.mapper.MetricsSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    private final List<AnomalyDetectionAlgorithm> algorithms;
    private final List<AnomalyDetector> legacyDetectors;
    private final MetricsSnapshotMapper snapshotMapper;
    private final MetricsAdapter metricsAdapter;

    public List<AnomalyResult> detectAnomalies(String namespace, int historyHours) {
        Instant end = Instant.now();
        Instant start = end.minus(historyHours, ChronoUnit.HOURS);
        List<MetricsSnapshot> historicalData = snapshotMapper.findByNamespaceAndTimeRange(namespace, start);

        if (historicalData.isEmpty()) {
            log.info("No historical data available for anomaly detection in namespace: {}", namespace);
            return new ArrayList<>();
        }

        MetricsSnapshot current = historicalData.get(historicalData.size() - 1);
        return runDetectionPipeline(historicalData, current);
    }

    private List<AnomalyResult> runDetectionPipeline(List<MetricsSnapshot> historicalData, MetricsSnapshot current) {
        List<AnomalyResult> results = new ArrayList<>();
        Set<String> metricNames = metricsAdapter.extractMetricNames(historicalData);

        for (String metricName : metricNames) {
            MetricSeries series = metricsAdapter.toMetricSeries(historicalData, metricName);
            double currentValue = metricsAdapter.extractMetricValue(current, metricName);

            for (AnomalyDetectionAlgorithm algorithm : algorithms) {
                try {
                    if (!algorithm.supports(metricName)) {
                        continue;
                    }

                    AnomalyResult result = algorithm.detect(series, currentValue);
                    if (result.isAnomaly()) {
                        results.add(result);
                        log.warn("Anomaly detected by {}: {}", algorithm.getName(), result.getDescription());
                    }
                } catch (Exception e) {
                    log.error("Error running algorithm {} for metric {}", algorithm.getName(), metricName, e);
                }
            }
        }

        return results;
    }

    public AnomalyResult detectWithAlgorithm(String algorithmName, List<MetricsSnapshot> history, MetricsSnapshot current) {
        return algorithms.stream()
                .filter(a -> a.getName().equalsIgnoreCase(algorithmName))
                .findFirst()
                .map(a -> {
                    Set<String> metricNames = metricsAdapter.extractMetricNames(history);
                    String firstMetric = metricNames.iterator().next();
                    MetricSeries series = metricsAdapter.toMetricSeries(history, firstMetric);
                    double currentValue = metricsAdapter.extractMetricValue(current, firstMetric);
                    return a.detect(series, currentValue);
                })
                .orElseThrow(() -> new IllegalArgumentException("Unknown algorithm: " + algorithmName));
    }

    public List<String> getAvailableAlgorithms() {
        return algorithms.stream().map(AnomalyDetectionAlgorithm::getName).toList();
    }

    @Deprecated
    public List<AnomalyResult> detectWithLegacyDetectors(String namespace, int historyHours) {
        Instant end = Instant.now();
        Instant start = end.minus(historyHours, ChronoUnit.HOURS);
        List<MetricsSnapshot> historicalData = snapshotMapper.findByNamespaceAndTimeRange(namespace, start);

        if (historicalData.isEmpty()) {
            return new ArrayList<>();
        }

        MetricsSnapshot current = historicalData.get(historicalData.size() - 1);
        List<AnomalyResult> results = new ArrayList<>();

        for (AnomalyDetector detector : legacyDetectors) {
            try {
                if (detector.supports("all")) {
                    AnomalyResult result = detector.detect(historicalData, current);
                    if (result.isAnomaly()) {
                        results.add(result);
                    }
                }
            } catch (Exception e) {
                log.error("Error running legacy detector {}", detector.getAlgorithmName(), e);
            }
        }

        return results;
    }
}
