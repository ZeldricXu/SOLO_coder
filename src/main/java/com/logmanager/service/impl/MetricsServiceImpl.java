package com.logmanager.service.impl;

import com.logmanager.domain.event.DomainEvent;
import com.logmanager.domain.event.EventPublisher;
import com.logmanager.domain.model.MetricsSnapshot;
import com.logmanager.domain.model.TimeSeriesMetric;
import com.logmanager.service.MetricsService;
import com.logmanager.service.metrics.BatchProcessor;
import com.logmanager.service.metrics.MetricsAggregator;
import com.logmanager.service.metrics.MetricsExporter;
import com.logmanager.service.metrics.MetricsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsServiceImpl implements MetricsService {

    private final MetricsRepository metricsRepository;
    private final MetricsAggregator metricsAggregator;
    private final MetricsExporter metricsExporter;
    private final EventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @Value("${metrics.batch.enabled:true}")
    private boolean batchEnabled;

    @Value("${metrics.batch.max-size:100}")
    private int maxBatchSize;

    @Value("${metrics.batch.flush-interval:1s}")
    private Duration flushInterval;

    @Value("${metrics.batch.max-latency:5s}")
    private Duration maxLatency;

    private BatchProcessor<TimeSeriesMetric, TimeSeriesMetric> batchProcessor;

    private final AtomicLong batchProcessedCount = new AtomicLong(0);
    private final AtomicLong batchTotalCount = new AtomicLong(0);
    private final AtomicLong asyncSubmittedCount = new AtomicLong(0);
    private Counter batchCounter;
    private Counter asyncCounter;

    @PostConstruct
    public void init() {
        log.info("MetricsService initialized with repository, aggregator, and exporter");

        this.batchCounter = Counter.builder("metrics.batch.processed")
                .register(meterRegistry);
        this.asyncCounter = Counter.builder("metrics.async.submitted")
                .register(meterRegistry);

        if (batchEnabled) {
            this.batchProcessor = new BatchProcessor<>(
                    "metrics",
                    maxBatchSize,
                    flushInterval,
                    maxLatency,
                    this::processBatch,
                    results -> {
                        batchProcessedCount.addAndGet(results.size());
                        batchCounter.increment(results.size());
                        results.forEach(metric -> eventPublisher.publish(
                                new DomainEvent("metric.recorded", metric.getMetricId(), "metric")));
                    }
            );
            batchProcessor.start();
            log.info("Batch processor started for metrics with maxSize={}, flushInterval={}",
                    maxBatchSize, flushInterval);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (batchProcessor != null && batchProcessor.isStarted()) {
            batchProcessor.stop();
            log.info("Batch processor stopped");
        }
    }

    @Override
    public Mono<TimeSeriesMetric> recordMetric(String metricName, Double value, Map<String, String> labels) {
        TimeSeriesMetric metric = createMetric(metricName, value, labels);

        return metricsRepository.save(metric)
                .then(metricsExporter.export(metric))
                .doOnSuccess(v -> eventPublisher.publish(
                        new DomainEvent("metric.recorded", metric.getMetricId(), "metric")))
                .then(Mono.just(metric));
    }

    @Override
    public Flux<TimeSeriesMetric> recordMetrics(Flux<TimeSeriesMetric> metrics) {
        return metrics.flatMap(metric -> recordMetric(metric.getMetricName(), metric.getValue(), metric.getLabels()));
    }

    @Override
    public Mono<MetricsSnapshot> createSnapshot() {
        return metricsRepository.findAll()
                .collectList()
                .flatMap(metricList -> metricsAggregator.createSnapshot(metricList))
                .flatMap(snapshot -> metricsRepository.saveSnapshot(snapshot)
                        .doOnSuccess(v -> eventPublisher.publish(
                                new DomainEvent("metrics.snapshot.created", snapshot.getSnapshotId(), "metrics")))
                        .then(Mono.just(snapshot)));
    }

    @Override
    public Flux<MetricsSnapshot> getSnapshots(Instant start, Instant end) {
        return metricsRepository.findSnapshotsByTimeRange(start, end);
    }

    @Override
    public Mono<Map<String, Object>> queryMetrics(String metricName, Instant start, Instant end, Map<String, String> labels) {
        return metricsRepository.findByMetricNameAndTimeRange(metricName, start, end)
                .filter(metric -> labels == null || labels.isEmpty()
                        || (metric.getLabels() != null && metric.getLabels().entrySet().containsAll(labels.entrySet())))
                .collectList()
                .flatMap(filtered -> metricsAggregator.aggregate(metricName, filtered));
    }

    @Override
    public Flux<TimeSeriesMetric> getMetricsByService(String serviceName) {
        return metricsRepository.findByServiceName(serviceName);
    }

    @Override
    public Mono<Void> recordMetricAsync(String metricName, Double value, Map<String, String> labels) {
        if (!batchEnabled || batchProcessor == null) {
            return recordMetric(metricName, value, labels).then();
        }

        TimeSeriesMetric metric = createMetric(metricName, value, labels);
        batchProcessor.submit(metric);
        asyncSubmittedCount.incrementAndGet();
        asyncCounter.increment();
        batchTotalCount.incrementAndGet();
        return Mono.empty();
    }

    @Override
    public Mono<List<TimeSeriesMetric>> recordMetricsBatch(List<TimeSeriesMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return Mono.just(List.of());
        }

        return Flux.fromIterable(metrics)
                .flatMap(metric -> recordMetric(metric.getMetricName(), metric.getValue(), metric.getLabels()))
                .collectList();
    }

    @Override
    public Mono<Map<String, Object>> getBatchStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("batchEnabled", batchEnabled);
        stats.put("maxBatchSize", maxBatchSize);
        stats.put("flushInterval", flushInterval.toString());
        stats.put("maxLatency", maxLatency.toString());
        stats.put("asyncSubmittedCount", asyncSubmittedCount.get());
        stats.put("batchProcessedCount", batchProcessedCount.get());
        stats.put("batchTotalCount", batchTotalCount.get());
        stats.put("batchProcessorStarted", batchProcessor != null && batchProcessor.isStarted());
        return Mono.just(stats);
    }

    private Mono<List<TimeSeriesMetric>> processBatch(List<TimeSeriesMetric> batch) {
        log.debug("Processing metrics batch of size {}", batch.size());
        return Flux.fromIterable(batch)
                .flatMap(metric -> metricsRepository.save(metric)
                        .then(metricsExporter.export(metric))
                        .thenReturn(metric))
                .collectList()
                .doOnNext(results -> log.debug("Successfully processed {} metrics in batch", results.size()));
    }

    private TimeSeriesMetric createMetric(String metricName, Double value, Map<String, String> labels) {
        TimeSeriesMetric metric = new TimeSeriesMetric();
        metric.setId(UUID.randomUUID().toString());
        metric.setMetricId(UUID.randomUUID().toString());
        metric.setMetricName(metricName);
        metric.setValue(value);
        metric.setTimestamp(Instant.now());
        metric.setLabels(labels);
        metric.setCreatedAt(Instant.now());
        return metric;
    }
}
