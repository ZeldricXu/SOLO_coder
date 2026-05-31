package com.device.platform.monitor;

import com.device.platform.common.JsonUtils;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.MetricsResponse;
import com.device.platform.entity.MetricsSnapshot;
import com.device.platform.mapper.MetricsSnapshotMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final MetricsSnapshotMapper metricsSnapshotMapper;
    private final MeterRegistry meterRegistry;

    @Value("${metrics.snapshot.interval-ms:60000}")
    private long snapshotIntervalMs;

    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final Map<String, AtomicDouble> gaugeCache = new ConcurrentHashMap<>();

    public void recordMetric(String name, double value, Map<String, String> tags) {
        String key = buildKey(name, tags);

        Timer timer = timerCache.computeIfAbsent(key, k ->
                Timer.builder(name)
                        .tags(convertTags(tags))
                        .register(meterRegistry));
        timer.record(Duration.ofMillis((long) value));

        AtomicDouble gauge = gaugeCache.computeIfAbsent(key, k -> {
            AtomicDouble atomicDouble = new AtomicDouble();
            Gauge.builder(name, atomicDouble, AtomicDouble::get)
                    .tags(convertTags(tags))
                    .register(meterRegistry);
            return atomicDouble;
        });
        gauge.set(value);
    }

    public void incrementCounter(String name, Map<String, String> tags) {
        String key = buildKey(name, tags);
        Counter counter = counterCache.computeIfAbsent(key, k ->
                Counter.builder(name)
                        .tags(convertTags(tags))
                        .register(meterRegistry));
        counter.increment();
    }

    public void incrementCounter(String name, double amount, Map<String, String> tags) {
        String key = buildKey(name, tags);
        Counter counter = counterCache.computeIfAbsent(key, k ->
                Counter.builder(name)
                        .tags(convertTags(tags))
                        .register(meterRegistry));
        counter.increment(amount);
    }

    @Scheduled(fixedDelayString = "${metrics.snapshot.interval-ms:60000}")
    @Transactional
    public void createMetricsSnapshot() {
        try {
            Map<String, Object> metrics = new HashMap<>();

            meterRegistry.getMeters().forEach(meter -> {
                meter.measure().forEach(measurement -> {
                    String metricName = meter.getId().getName() + "." + measurement.getStatistic().name().toLowerCase();
                    metrics.put(metricName, measurement.getValue());
                });
            });

            Map<String, Object> throughputMetrics = calculateThroughputMetrics();
            metrics.putAll(throughputMetrics);

            Map<String, String> dimensions = new HashMap<>();
            dimensions.put("host", System.getenv().getOrDefault("HOSTNAME", "localhost"));
            dimensions.put("region", System.getenv().getOrDefault("REGION", "cn-east"));
            dimensions.put("service", "device-platform");

            MetricsSnapshot snapshot = new MetricsSnapshot();
            snapshot.setSnapshotId(generateSnapshotId());
            snapshot.setTimestamp(Instant.now());
            snapshot.setMetrics(JsonUtils.toJson(metrics));
            snapshot.setDimensions(JsonUtils.toJson(dimensions));
            snapshot.setMetricType("system");
            snapshot.setWindowSizeMs(snapshotIntervalMs);

            metricsSnapshotMapper.insert(snapshot);
            log.debug("指标快照已创建: snapshotId={}, metricsCount={}",
                    snapshot.getSnapshotId(), metrics.size());

        } catch (Exception e) {
            log.error("创建指标快照失败: {}", e.getMessage(), e);
        }
    }

    private Map<String, Object> calculateThroughputMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        long requestCount = counterCache.values().stream()
                .filter(c -> c.getId().getName().contains("request.count"))
                .mapToLong(Counter::count)
                .sum();

        long errorCount = counterCache.values().stream()
                .filter(c -> c.getId().getName().contains("request.errors"))
                .mapToLong(Counter::count)
                .sum();

        double avgLatency = timerCache.values().stream()
                .filter(t -> t.getId().getName().contains("request.duration"))
                .mapToDouble(t -> t.mean(io.micrometer.core.instrument.util.TimeUtils.MILLISECONDS_PER_SECOND))
                .average()
                .orElse(0.0);

        double p99Latency = timerCache.values().stream()
                .filter(t -> t.getId().getName().contains("request.duration"))
                .mapToDouble(t -> t.takeSnapshot().percentileValues()[
                        t.takeSnapshot().percentileValues().length - 1].value(
                                io.micrometer.core.instrument.util.TimeUtils.MILLISECONDS_PER_SECOND))
                .max()
                .orElse(0.0);

        double errorRate = requestCount > 0 ? (double) errorCount / requestCount : 0.0;

        metrics.put("throughput", (double) requestCount / (snapshotIntervalMs / 1000.0));
        metrics.put("request.total", (double) requestCount);
        metrics.put("request.errors", (double) errorCount);
        metrics.put("latency_avg", avgLatency);
        metrics.put("latency_p99", p99Latency);
        metrics.put("error_rate", errorRate);

        return metrics;
    }

    public Mono<MetricsResponse> getLatestSnapshot(String metricType, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            MetricsSnapshot snapshot = metricsSnapshotMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MetricsSnapshot>()
                            .eq(metricType != null, MetricsSnapshot::getMetricType, metricType)
                            .orderByDesc(MetricsSnapshot::getTimestamp)
                            .last("LIMIT 1"));

            if (snapshot == null) {
                throw new com.device.platform.common.BusinessException(404, "没有找到指标快照", ctx.getTraceId());
            }

            return toMetricsResponse(snapshot);
        });
    }

    public Flux<MetricsResponse> getSnapshots(String metricType, Long startTimeMs,
                                              Long endTimeMs, TraceContext ctx) {
        return Flux.fromIterable(metricsSnapshotMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MetricsSnapshot>()
                        .eq(metricType != null, MetricsSnapshot::getMetricType, metricType)
                        .ge(startTimeMs != null, MetricsSnapshot::getTimestamp,
                                startTimeMs != null ? Instant.ofEpochMilli(startTimeMs) : null)
                        .le(endTimeMs != null, MetricsSnapshot::getTimestamp,
                                endTimeMs != null ? Instant.ofEpochMilli(endTimeMs) : null)
                        .orderByAsc(MetricsSnapshot::getTimestamp)
                        .last("LIMIT 1000"))
                .stream()
                .map(this::toMetricsResponse)
                .toList());
    }

    public Mono<Map<String, Object>> getRealtimeMetrics(TraceContext ctx) {
        return Mono.fromCallable(() -> {
            Map<String, Object> metrics = new LinkedHashMap<>();

            meterRegistry.getMeters().forEach(meter -> {
                meter.measure().forEach(measurement -> {
                    String metricName = meter.getId().getName();
                    if (!metricName.startsWith("jvm") && !metricName.startsWith("system")) {
                        metrics.put(metricName + "." + measurement.getStatistic().name().toLowerCase(),
                                measurement.getValue());
                    }
                });
            });

            return metrics;
        });
    }

    public Mono<Boolean> checkRateLimit(String resourceKey, int maxRequests,
                                        long windowMs, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            String counterName = "rate_limit." + resourceKey;
            Map<String, String> tags = Collections.singletonMap("resource", resourceKey);

            String key = buildKey(counterName, tags);
            Counter counter = counterCache.computeIfAbsent(key, k ->
                    Counter.builder(counterName)
                            .tags(convertTags(tags))
                            .register(meterRegistry));

            return counter.count() < maxRequests;
        });
    }

    private MetricsResponse toMetricsResponse(MetricsSnapshot snapshot) {
        MetricsResponse response = new MetricsResponse();
        response.setSnapshotId(snapshot.getSnapshotId());
        response.setTimestamp(snapshot.getTimestamp());

        if (snapshot.getMetrics() != null) {
            response.setMetrics(JsonUtils.fromJson(snapshot.getMetrics(), Map.class));
        }
        if (snapshot.getDimensions() != null) {
            response.setDimensions(JsonUtils.fromJson(snapshot.getDimensions(), Map.class));
        }

        return response;
    }

    private String buildKey(String name, Map<String, String> tags) {
        StringBuilder sb = new StringBuilder(name);
        if (tags != null && !tags.isEmpty()) {
            tags.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("|").append(e.getKey()).append("=").append(e.getValue()));
        }
        return sb.toString();
    }

    private List<io.micrometer.core.instrument.Tag> convertTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        return tags.entrySet().stream()
                .map(e -> io.micrometer.core.instrument.Tag.of(e.getKey(), e.getValue()))
                .toList();
    }

    private String generateSnapshotId() {
        return "snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    public static class AtomicDouble {
        private volatile double value;

        public double get() {
            return value;
        }

        public void set(double value) {
            this.value = value;
        }
    }
}
