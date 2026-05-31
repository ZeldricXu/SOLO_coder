package com.tracetopology.core.service.impl;

import com.tracetopology.api.service.MetricsService;
import com.tracetopology.core.validation.ParamValidator;
import com.tracetopology.domain.entity.Snapshot;
import com.tracetopology.spi.repository.MetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class MetricsServiceImpl implements MetricsService {

    private final MetricsRepository metricsRepository;

    private final Map<String, WindowAggregator> aggregators = new ConcurrentHashMap<>();
    private final long windowSizeMs = 60000;

    @Override
    public void ingestMetric(String metricName, double value, Map<String, String> dimensions, long timestamp) {
        ParamValidator.validateNotBlank(metricName, "metricName");
        ParamValidator.validateNotNull(dimensions, "dimensions");

        String key = buildKey(metricName, dimensions);
        aggregators.computeIfAbsent(key, k -> new WindowAggregator(windowSizeMs))
                .record(value, timestamp);

        metricsRepository.saveMetric(metricName, value, dimensions, timestamp);
    }

    @Override
    public void ingestMetrics(List<Map<String, Object>> metricsBatch) {
        ParamValidator.validateNotNull(metricsBatch, "metricsBatch");

        for (Map<String, Object> metric : metricsBatch) {
            try {
                String metricName = (String) metric.get("name");
                double value = ((Number) metric.get("value")).doubleValue();
                @SuppressWarnings("unchecked")
                Map<String, String> dimensions = (Map<String, String>) metric.getOrDefault("dimensions", new HashMap<>());
                long timestamp = metric.containsKey("timestamp")
                        ? ((Number) metric.get("timestamp")).longValue()
                        : System.currentTimeMillis();

                ingestMetric(metricName, value, dimensions, timestamp);
            } catch (Exception e) {
                log.warn("指标处理失败: metric={}, error={}", metric, e.getMessage());
            }
        }

        metricsRepository.saveMetricsBatch(metricsBatch);
    }

    @Override
    public Snapshot createSnapshot(Map<String, String> dimensions) {
        ParamValidator.validateNotNull(dimensions, "dimensions");

        Map<String, Double> metrics = new HashMap<>();
        for (Map.Entry<String, WindowAggregator> entry : aggregators.entrySet()) {
            WindowAggregator aggregator = entry.getValue();
            metrics.put(entry.getKey(), aggregator.getAverage());
        }

        Snapshot snapshot = Snapshot.create(metrics, dimensions);
        return metricsRepository.saveSnapshot(snapshot);
    }

    @Override
    public List<Snapshot> querySnapshots(String metricName, Instant startTime, Instant endTime,
                                          Map<String, String> dimensions) {
        ParamValidator.validateNotBlank(metricName, "metricName");
        ParamValidator.validateNotNull(startTime, "startTime");
        ParamValidator.validateNotNull(endTime, "endTime");

        return metricsRepository.findSnapshots(metricName, startTime, endTime, dimensions);
    }

    @Override
    public Map<String, Object> getAggregatedMetrics(String metricName, Instant startTime, Instant endTime,
                                                     Map<String, String> dimensions, String aggregator) {
        ParamValidator.validateNotBlank(metricName, "metricName");
        ParamValidator.validateNotNull(startTime, "startTime");
        ParamValidator.validateNotNull(endTime, "endTime");
        ParamValidator.validateNotBlank(aggregator, "aggregator");

        return metricsRepository.aggregateMetrics(metricName, startTime, endTime, dimensions, aggregator);
    }

    @Override
    public double getMetricValue(String metricName, Map<String, String> dimensions) {
        ParamValidator.validateNotBlank(metricName, "metricName");
        ParamValidator.validateNotNull(dimensions, "dimensions");

        return metricsRepository.getCurrentMetricValue(metricName, dimensions);
    }

    public Map<String, Object> getPreAggregatedValues(String metricName, Map<String, String> dimensions) {
        String key = buildKey(metricName, dimensions);
        WindowAggregator aggregator = aggregators.get(key);
        if (aggregator == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("avg", aggregator.getAverage());
        result.put("min", aggregator.getMin());
        result.put("max", aggregator.getMax());
        result.put("sum", aggregator.getSum());
        result.put("count", aggregator.getCount());
        result.put("p95", aggregator.getPercentile(95));
        result.put("p99", aggregator.getPercentile(99));

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

    private static class WindowAggregator {
        private final long windowSizeMs;
        private final AtomicLong count = new AtomicLong(0);
        private double sum = 0;
        private double min = Double.MAX_VALUE;
        private double max = Double.MIN_VALUE;
        private final List<Double> values = new ArrayList<>();
        private long windowStart;

        public WindowAggregator(long windowSizeMs) {
            this.windowSizeMs = windowSizeMs;
            this.windowStart = System.currentTimeMillis();
        }

        public synchronized void record(double value, long timestamp) {
            if (timestamp - windowStart > windowSizeMs) {
                reset();
            }
            count.incrementAndGet();
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
            values.add(value);
        }

        private void reset() {
            count.set(0);
            sum = 0;
            min = Double.MAX_VALUE;
            max = Double.MIN_VALUE;
            values.clear();
            windowStart = System.currentTimeMillis();
        }

        public synchronized double getAverage() {
            long c = count.get();
            return c > 0 ? sum / c : 0;
        }

        public synchronized double getMin() {
            return min == Double.MAX_VALUE ? 0 : min;
        }

        public synchronized double getMax() {
            return max == Double.MIN_VALUE ? 0 : max;
        }

        public synchronized double getSum() {
            return sum;
        }

        public synchronized long getCount() {
            return count.get();
        }

        public synchronized double getPercentile(int percentile) {
            if (values.isEmpty()) return 0;
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }
}
