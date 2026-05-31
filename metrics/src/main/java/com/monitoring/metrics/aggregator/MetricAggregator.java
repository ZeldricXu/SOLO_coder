package com.monitoring.metrics.aggregator;

import com.monitoring.metrics.model.MetricPoint;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

@Component
public class MetricAggregator {

    private final Map<String, AggregatedMetric> aggregations = new ConcurrentHashMap<>();

    public void aggregate(MetricPoint point) {
        String key = buildKey(point);
        aggregations.compute(key, (k, existing) -> {
            if (existing == null) {
                existing = new AggregatedMetric(point.getName(), point.getDimensions());
            }
            existing.add(point.getValue());
            return existing;
        });
    }

    public void aggregateAll(List<MetricPoint> points) {
        points.forEach(this::aggregate);
    }

    public AggregatedMetric getAggregation(String name, Map<String, String> dimensions) {
        String key = buildKey(name, dimensions);
        return aggregations.get(key);
    }

    public List<AggregatedMetric> getAllAggregations() {
        return new ArrayList<>(aggregations.values());
    }

    public List<AggregatedMetric> getAggregationsByName(String name) {
        return aggregations.values().stream()
                .filter(a -> a.getName().equals(name))
                .toList();
    }

    public void reset() {
        aggregations.clear();
    }

    public void reset(String name) {
        aggregations.entrySet().removeIf(e -> e.getValue().getName().equals(name));
    }

    private String buildKey(MetricPoint point) {
        return buildKey(point.getName(), point.getDimensions());
    }

    private String buildKey(String name, Map<String, String> dimensions) {
        StringBuilder sb = new StringBuilder(name);
        if (dimensions != null && !dimensions.isEmpty()) {
            TreeMap<String, String> sorted = new TreeMap<>(dimensions);
            sorted.forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        }
        return sb.toString();
    }

    public static class AggregatedMetric {
        private final String name;
        private final Map<String, String> dimensions;
        private final DoubleAdder sum = new DoubleAdder();
        private final DoubleAdder sumOfSquares = new DoubleAdder();
        private final LongAdder count = new LongAdder();
        private volatile double min = Double.MAX_VALUE;
        private volatile double max = Double.MIN_VALUE;
        private final AtomicLong lastUpdate = new AtomicLong(System.currentTimeMillis());

        public AggregatedMetric(String name, Map<String, String> dimensions) {
            this.name = name;
            this.dimensions = dimensions != null ? new HashMap<>(dimensions) : Collections.emptyMap();
        }

        public synchronized void add(double value) {
            sum.add(value);
            sumOfSquares.add(value * value);
            count.increment();
            min = Math.min(min, value);
            max = Math.max(max, value);
            lastUpdate.set(System.currentTimeMillis());
        }

        public String getName() { return name; }
        public Map<String, String> getDimensions() { return Collections.unmodifiableMap(dimensions); }
        public double getSum() { return sum.sum(); }
        public long getCount() { return count.sum(); }
        public double getAvg() { return count.sum() > 0 ? sum.sum() / count.sum() : 0; }
        public double getMin() { return min == Double.MAX_VALUE ? 0 : min; }
        public double getMax() { return max == Double.MIN_VALUE ? 0 : max; }
        public double getStdDev() {
            long c = count.sum();
            if (c < 2) return 0;
            double variance = (sumOfSquares.sum() - (sum.sum() * sum.sum()) / c) / (c - 1);
            return Math.sqrt(Math.max(0, variance));
        }
        public long getLastUpdate() { return lastUpdate.get(); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("name", name);
            map.put("dimensions", dimensions);
            map.put("sum", getSum());
            map.put("count", getCount());
            map.put("avg", getAvg());
            map.put("min", getMin());
            map.put("max", getMax());
            map.put("stdDev", getStdDev());
            map.put("lastUpdate", getLastUpdate());
            return map;
        }
    }
}
