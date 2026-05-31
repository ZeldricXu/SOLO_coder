package com.tracetopology.infrastructure.persistence.repository;

import com.tracetopology.domain.entity.Snapshot;
import com.tracetopology.spi.repository.MetricsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Repository
public class MetricsRepositoryImpl implements MetricsRepository {

    private final Map<String, List<MetricPoint>> metricStore = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshotStore = new ConcurrentHashMap<>();

    @Override
    public void saveMetric(String metricName, double value, Map<String, String> dimensions, long timestamp) {
        String key = buildKey(metricName, dimensions);
        metricStore.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new MetricPoint(value, timestamp));
    }

    @Override
    public void saveMetricsBatch(List<Map<String, Object>> metricsBatch) {
        for (Map<String, Object> metric : metricsBatch) {
            try {
                String metricName = (String) metric.get("name");
                double value = ((Number) metric.get("value")).doubleValue();
                @SuppressWarnings("unchecked")
                Map<String, String> dimensions = (Map<String, String>) metric.getOrDefault("dimensions", new HashMap<>());
                long timestamp = metric.containsKey("timestamp")
                        ? ((Number) metric.get("timestamp")).longValue()
                        : System.currentTimeMillis();
                saveMetric(metricName, value, dimensions, timestamp);
            } catch (Exception e) {
                log.warn("批量指标保存失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public Snapshot saveSnapshot(Snapshot snapshot) {
        snapshotStore.put(snapshot.getSnapshotId(), snapshot);
        return snapshot;
    }

    @Override
    public Optional<Snapshot> findSnapshotById(String snapshotId) {
        return Optional.ofNullable(snapshotStore.get(snapshotId));
    }

    @Override
    public List<Snapshot> findSnapshots(String metricName, Instant startTime, Instant endTime,
                                         Map<String, String> dimensions) {
        long start = startTime.toEpochMilli();
        long end = endTime.toEpochMilli();

        return snapshotStore.values().stream()
                .filter(s -> s.getTimestamp().toEpochMilli() >= start && s.getTimestamp().toEpochMilli() <= end)
                .filter(s -> s.getMetrics().containsKey(buildKey(metricName, dimensions)))
                .toList();
    }

    @Override
    public double getCurrentMetricValue(String metricName, Map<String, String> dimensions) {
        String key = buildKey(metricName, dimensions);
        List<MetricPoint> points = metricStore.get(key);
        if (points == null || points.isEmpty()) {
            return 0;
        }
        synchronized (points) {
            return points.get(points.size() - 1).value;
        }
    }

    @Override
    public Map<String, Object> aggregateMetrics(String metricName, Instant startTime, Instant endTime,
                                                 Map<String, String> dimensions, String aggregator) {
        String key = buildKey(metricName, dimensions);
        List<MetricPoint> points = metricStore.getOrDefault(key, List.of());
        long start = startTime.toEpochMilli();
        long end = endTime.toEpochMilli();

        List<Double> filtered = points.stream()
                .filter(p -> p.timestamp >= start && p.timestamp <= end)
                .map(p -> p.value)
                .toList();

        Map<String, Object> result = new HashMap<>();
        if (filtered.isEmpty()) {
            result.put("value", 0);
            result.put("count", 0);
            return result;
        }

        double value = switch (aggregator.toLowerCase()) {
            case "sum" -> filtered.stream().mapToDouble(Double::doubleValue).sum();
            case "avg" -> filtered.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case "min" -> filtered.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case "max" -> filtered.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case "count" -> filtered.size();
            default -> filtered.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        };

        result.put("value", value);
        result.put("count", filtered.size());
        result.put("aggregator", aggregator);
        return result;
    }

    @Override
    public List<Map<String, Object>> getMetricHistory(String metricName, Map<String, String> dimensions, int points) {
        String key = buildKey(metricName, dimensions);
        List<MetricPoint> allPoints = metricStore.getOrDefault(key, List.of());

        List<Map<String, Object>> result = new ArrayList<>();
        int start = Math.max(0, allPoints.size() - points);

        synchronized (allPoints) {
            for (int i = start; i < allPoints.size(); i++) {
                MetricPoint point = allPoints.get(i);
                Map<String, Object> entry = new HashMap<>();
                entry.put("value", point.value);
                entry.put("timestamp", point.timestamp);
                result.add(entry);
            }
        }

        return result;
    }

    private String buildKey(String metricName, Map<String, String> dimensions) {
        List<String> sortedKeys = new ArrayList<>(dimensions.keySet());
        Collections.sort(sortedKeys);

        StringBuilder sb = new StringBuilder(metricName);
        for (String key : sortedKeys) {
            sb.append(':').append(key).append('=').append(dimensions.get(key));
        }
        return sb.toString();
    }

    private record MetricPoint(double value, long timestamp) {}
}
