package com.scheduler.anomaly.detection.service;

import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.anomaly.detection.adapter.MetricsAdapter;
import com.scheduler.anomaly.detection.algorithm.AnomalyDetectionAlgorithm;
import com.scheduler.anomaly.detection.batch.BatchDetectionRequest;
import com.scheduler.anomaly.detection.batch.BatchDetectionResult;
import com.scheduler.anomaly.detection.batch.RequestBatcher;
import com.scheduler.anomaly.detection.model.MetricSeries;
import com.scheduler.persistence.entity.MetricsSnapshot;
import com.scheduler.persistence.mapper.MetricsSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchAnomalyDetectionService {

    private final List<AnomalyDetectionAlgorithm> algorithms;
    private final MetricsSnapshotMapper snapshotMapper;
    private final MetricsAdapter metricsAdapter;
    private final RequestBatcher requestBatcher;

    public Mono<BatchDetectionResult> detectBatch(List<BatchDetectionRequest> requests) {
        return Mono.fromCallable(() -> {
            String batchId = "batch_" + UUID.randomUUID().toString().substring(0, 8);
            long startTime = System.currentTimeMillis();
            log.info("Starting batch detection: {} with {} requests", batchId, requests.size());

            List<AnomalyResult> allResults = new ArrayList<>();
            Map<String, Integer> algorithmStats = new HashMap<>();
            int totalAnomalies = 0;

            for (BatchDetectionRequest request : requests) {
                try {
                    List<AnomalyResult> results = processSingleRequest(request);
                    allResults.addAll(results);
                    totalAnomalies += results.stream().filter(AnomalyResult::isAnomaly).count();

                    for (AnomalyResult result : results) {
                        algorithmStats.merge(result.getAlgorithm(), 1, Integer::sum);
                    }
                } catch (Exception e) {
                    log.error("Error processing detection request for metric: {}", request.getMetricName(), e);
                }
            }

            long processingTime = System.currentTimeMillis() - startTime;

            return BatchDetectionResult.builder()
                    .batchId(batchId)
                    .totalRequests(requests.size())
                    .anomalyCount(totalAnomalies)
                    .results(allResults)
                    .algorithmStats(algorithmStats)
                    .processingTimeMs(processingTime)
                    .success(true)
                    .build();
        });
    }

    public Mono<List<AnomalyResult>> detectWithBatching(BatchDetectionRequest request) {
        return requestBatcher.submit(request);
    }

    public Mono<BatchDetectionResult> detectForNamespace(String namespace, int historyHours) {
        return Mono.fromCallable(() -> {
            Instant end = Instant.now();
            Instant start = end.minus(historyHours, ChronoUnit.HOURS);
            List<MetricsSnapshot> snapshots = snapshotMapper.findByNamespaceAndTimeRange(namespace, start);

            if (snapshots.isEmpty()) {
                return BatchDetectionResult.builder()
                        .batchId("batch_empty")
                        .namespace(namespace)
                        .totalRequests(0)
                        .anomalyCount(0)
                        .results(Collections.emptyList())
                        .algorithmStats(Collections.emptyMap())
                        .processingTimeMs(0)
                        .success(true)
                        .build();
            }

            Set<String> metricNames = metricsAdapter.extractMetricNames(snapshots);
            List<BatchDetectionRequest> requests = new ArrayList<>();

            for (String metricName : metricNames) {
                MetricSeries series = metricsAdapter.toMetricSeries(snapshots, metricName);
                if (series.size() < 5) continue;

                MetricsSnapshot latest = snapshots.get(snapshots.size() - 1);
                double currentValue = metricsAdapter.extractMetricValue(latest, metricName);

                BatchDetectionRequest request = BatchDetectionRequest.builder()
                        .namespace(namespace)
                        .metricName(metricName)
                        .historicalValues(series.getValues())
                        .historicalTimestamps(series.getTimestamps())
                        .currentValue(currentValue)
                        .currentTimestamp(latest.getTimestamp())
                        .algorithms(algorithms.stream().map(AnomalyDetectionAlgorithm::getName).collect(Collectors.toList()))
                        .build();

                requests.add(request);
            }

            return detectBatch(requests).block();
        });
    }

    public Flux<AnomalyResult> detectStream(Flux<BatchDetectionRequest> requestFlux) {
        return requestFlux
                .concatMap(request -> Mono.fromCallable(() -> processSingleRequest(request))
                        .flatMapMany(Flux::fromIterable));
    }

    private List<AnomalyResult> processSingleRequest(BatchDetectionRequest request) {
        List<AnomalyResult> results = new ArrayList<>();
        MetricSeries series = MetricSeries.builder()
                .metricName(request.getMetricName())
                .values(request.getHistoricalValues())
                .timestamps(request.getHistoricalTimestamps())
                .build();

        List<String> algorithmNames = request.getAlgorithms() != null && !request.getAlgorithms().isEmpty()
                ? request.getAlgorithms()
                : algorithms.stream().map(AnomalyDetectionAlgorithm::getName).collect(Collectors.toList());

        for (String algorithmName : algorithmNames) {
            for (AnomalyDetectionAlgorithm algorithm : algorithms) {
                if (algorithm.getName().equalsIgnoreCase(algorithmName)) {
                    try {
                        AnomalyResult result = algorithm.detect(series, request.getCurrentValue());
                        results.add(result);
                    } catch (Exception e) {
                        log.warn("Algorithm {} failed for metric {}", algorithmName, request.getMetricName(), e);
                    }
                    break;
                }
            }
        }

        return results;
    }

    public Map<String, Object> getBatchStats() {
        return Map.of(
                "pendingRequests", requestBatcher.getPendingRequestCount(),
                "availableAlgorithms", algorithms.stream().map(AnomalyDetectionAlgorithm::getName).collect(Collectors.toList())
        );
    }
}
