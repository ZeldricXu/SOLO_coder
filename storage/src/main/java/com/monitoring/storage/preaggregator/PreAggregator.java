package com.monitoring.storage.preaggregator;

import com.monitoring.storage.model.TimeSeriesPoint;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

@Component
public class PreAggregator {

    private final Map<String, AggregationBucket> minuteBuckets = new ConcurrentHashMap<>();
    private final Map<String, AggregationBucket> hourBuckets = new ConcurrentHashMap<>();

    public void aggregate(TimeSeriesPoint point) {
        long minute = point.getTimestamp() / 60000 * 60000;
        long hour = point.getTimestamp() / 3600000 * 3600000;

        String minuteKey = point.getMetric() + "|" + minute;
        String hourKey = point.getMetric() + "|" + hour;

        minuteBuckets.compute(minuteKey, (k, existing) -> {
            if (existing == null) {
                existing = new AggregationBucket(minute, point.getTags());
            }
            existing.add(point.getValue());
            return existing;
        });

        hourBuckets.compute(hourKey, (k, existing) -> {
            if (existing == null) {
                existing = new AggregationBucket(hour, point.getTags());
            }
            existing.add(point.getValue());
            return existing;
        });
    }

    public List<TimeSeriesPoint> getMinuteAggregations(String metric, Instant startTime, Instant endTime) {
        long startMs = startTime.toEpochMilli() / 60000 * 60000;
        long endMs = endTime.toEpochMilli();
        List<TimeSeriesPoint> result = new ArrayList<>();

        for (long t = startMs; t < endMs; t += 60000) {
            String key = metric + "|" + t;
            AggregationBucket bucket = minuteBuckets.get(key);
            if (bucket != null) {
                result.add(TimeSeriesPoint.builder()
                        .metric(metric + "_avg")
                        .value(bucket.getAvg())
                        .timestamp(t)
                        .tags(bucket.getTags())
                        .build());
            }
        }
        return result;
    }

    public List<TimeSeriesPoint> getHourAggregations(String metric, Instant startTime, Instant endTime) {
        long startMs = startTime.toEpochMilli() / 3600000 * 3600000;
        long endMs = endTime.toEpochMilli();
        List<TimeSeriesPoint> result = new ArrayList<>();

        for (long t = startMs; t < endMs; t += 3600000) {
            String key = metric + "|" + t;
            AggregationBucket bucket = hourBuckets.get(key);
            if (bucket != null) {
                result.add(TimeSeriesPoint.builder()
                        .metric(metric + "_avg")
                        .value(bucket.getAvg())
                        .timestamp(t)
                        .tags(bucket.getTags())
                        .build());
            }
        }
        return result;
    }

    public void purgeOld(Duration retention) {
        long threshold = System.currentTimeMillis() - retention.toMillis();
        minuteBuckets.entrySet().removeIf(e -> e.getValue().getTimestamp() < threshold);
        hourBuckets.entrySet().removeIf(e -> e.getValue().getTimestamp() < threshold);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("minuteBuckets", minuteBuckets.size());
        stats.put("hourBuckets", hourBuckets.size());
        return stats;
    }

    private static class AggregationBucket {
        private final long timestamp;
        private final Map<String, String> tags;
        private final DoubleAdder sum = new DoubleAdder();
        private final LongAdder count = new LongAdder();

        public AggregationBucket(long timestamp, Map<String, String> tags) {
            this.timestamp = timestamp;
            this.tags = tags != null ? new HashMap<>(tags) : Collections.emptyMap();
        }

        public void add(double value) {
            sum.add(value);
            count.increment();
        }

        public double getAvg() {
            long c = count.sum();
            return c > 0 ? sum.sum() / c : 0;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public Map<String, String> getTags() {
            return tags;
        }
    }
}
