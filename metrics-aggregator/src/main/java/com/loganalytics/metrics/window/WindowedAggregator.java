package com.loganalytics.metrics.window;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.model.MetricPoint;
import com.loganalytics.common.util.IdUtils;
import com.loganalytics.metrics.config.MetricsConfig;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class WindowedAggregator {
    private static final Logger log = LoggerFactory.getLogger(WindowedAggregator.class);

    private final MetricsConfig config;

    public static class AggregationKey {
        private final String serviceName;
        private final LogLevel level;
        private final String errorCode;
        private final String patternId;

        public AggregationKey(String serviceName, LogLevel level, String errorCode, String patternId) {
            this.serviceName = serviceName;
            this.level = level;
            this.errorCode = errorCode;
            this.patternId = patternId;
        }

        public String toCompositeKey() {
            return (serviceName != null ? serviceName : "unknown") + "|" +
                   (level != null ? level : LogLevel.UNKNOWN) + "|" +
                   (errorCode != null ? errorCode : "none") + "|" +
                   (patternId != null ? patternId : "none");
        }

        public static AggregationKey fromCompositeKey(String key) {
            String[] parts = key.split("\\|", -1);
            return new AggregationKey(
                    parts[0].equals("unknown") ? null : parts[0],
                    LogLevel.valueOf(parts[1]),
                    parts[2].equals("none") ? null : parts[2],
                    parts[3].equals("none") ? null : parts[3]
            );
        }

        public String getServiceName() { return serviceName; }
        public LogLevel getLevel() { return level; }
        public String getErrorCode() { return errorCode; }
        public String getPatternId() { return patternId; }
    }

    public static class AggregateValue {
        long count;
        long errorCount;
        long warnCount;
        long bytesProcessed;
        Set<String> uniquePatterns;
        Map<String, Long> patternCounts;
        long firstTimestamp;
        long lastTimestamp;

        public AggregateValue() {
            this.uniquePatterns = new HashSet<>();
            this.patternCounts = new HashMap<>();
            this.firstTimestamp = Long.MAX_VALUE;
            this.lastTimestamp = Long.MIN_VALUE;
        }

        public AggregateValue add(LogEvent event) {
            count++;
            if (event.getLevel() != null) {
                if (event.getLevel().isMoreSevereThan(LogLevel.WARN)) {
                    errorCount++;
                } else if (event.getLevel() == LogLevel.WARN) {
                    warnCount++;
                }
            }
            if (event.getMessage() != null) {
                bytesProcessed += event.getMessage().getBytes().length;
            }
            if (event.getPatternId() != null) {
                uniquePatterns.add(event.getPatternId());
                patternCounts.merge(event.getPatternId(), 1L, Long::sum);
            }
            long ts = event.getTimestamp() != null ? event.getTimestamp().toEpochMilli() : System.currentTimeMillis();
            firstTimestamp = Math.min(firstTimestamp, ts);
            lastTimestamp = Math.max(lastTimestamp, ts);
            return this;
        }

        public AggregateValue merge(AggregateValue other) {
            count += other.count;
            errorCount += other.errorCount;
            warnCount += other.warnCount;
            bytesProcessed += other.bytesProcessed;
            uniquePatterns.addAll(other.uniquePatterns);
            other.patternCounts.forEach((k, v) -> patternCounts.merge(k, v, Long::sum));
            firstTimestamp = Math.min(firstTimestamp, other.firstTimestamp);
            lastTimestamp = Math.max(lastTimestamp, other.lastTimestamp);
            return this;
        }

        public double getErrorRate() {
            return count > 0 ? (double) errorCount / count : 0.0;
        }

        public double getEps(Duration windowSize) {
            long durationMs = lastTimestamp - firstTimestamp;
            if (durationMs <= 0) durationMs = windowSize.toMillis();
            return (double) count / (durationMs / 1000.0);
        }

        public List<Map.Entry<String, Long>> getTopPatterns(int k) {
            return patternCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(k)
                    .toList();
        }
    }

    public WindowedAggregator(MetricsConfig config) {
        this.config = config;
    }

    public KStream<String, MetricPoint> buildAggregationPipeline(KStream<String, LogEvent> inputStream) {
        List<KStream<String, MetricPoint>> allMetrics = new ArrayList<>();

        for (MetricsConfig.WindowConfig windowConfig : config.getWindows()) {
            KStream<String, MetricPoint> windowMetrics = buildWindowAggregation(
                    inputStream, windowConfig
            );
            allMetrics.add(windowMetrics);
        }

        KStream<String, MetricPoint> result = allMetrics.get(0);
        for (int i = 1; i < allMetrics.size(); i++) {
            result = result.merge(allMetrics.get(i));
        }

        return result;
    }

    private KStream<String, MetricPoint> buildWindowAggregation(
            KStream<String, LogEvent> inputStream,
            MetricsConfig.WindowConfig windowConfig) {

        KStream<AggregationKey, LogEvent> keyedStream = inputStream
                .map((key, event) -> {
                    AggregationKey aggKey = new AggregationKey(
                            event.getServiceName(),
                            event.getLevel(),
                            (String) event.getField("errorCode"),
                            event.getPatternId()
                    );
                    return KeyValue.pair(aggKey, event);
                });

        KGroupedStream<AggregationKey, LogEvent> groupedStream = keyedStream.groupByKey();

        KTable<Windowed<AggregationKey>, AggregateValue> aggregatedTable;
        Windows<Window> windows;

        switch (windowConfig.getType()) {
            case TUMBLING -> {
                windows = TimeWindows.ofSizeWithNoGrace(windowConfig.getSize());
                aggregatedTable = groupedStream
                        .windowedBy(windows)
                        .aggregate(
                                AggregateValue::new,
                                (key, event, aggregate) -> aggregate.add(event),
                                Materialized.as(windowConfig.getName() + "-tumbling-agg")
                        );
            }
            case HOPPING -> {
                windows = TimeWindows.ofSizeAndGraceWithNoGrace(
                        windowConfig.getSize(),
                        windowConfig.getAdvance()
                );
                aggregatedTable = groupedStream
                        .windowedBy(windows)
                        .aggregate(
                                AggregateValue::new,
                                (key, event, aggregate) -> aggregate.add(event),
                                Materialized.as(windowConfig.getName() + "-hopping-agg")
                        );
            }
            case SESSION -> {
                SessionWindows sessionWindows = SessionWindows
                        .ofInactivityGapWithNoGrace(windowConfig.getSize());
                aggregatedTable = groupedStream
                        .windowedBy(sessionWindows)
                        .aggregate(
                                AggregateValue::new,
                                (key, event, aggregate) -> aggregate.add(event),
                                (key, agg1, agg2) -> agg1.merge(agg2),
                                Materialized.as(windowConfig.getName() + "-session-agg")
                        );
            }
            default -> throw new IllegalArgumentException("Unknown window type: " + windowConfig.getType());
        }

        return aggregatedTable
                .toStream()
                .map((windowedKey, aggregate) -> {
                    AggregationKey key = windowedKey.key();
                    Window window = windowedKey.window();

                    List<MetricPoint> points = createMetricPoints(
                            key, aggregate, window, windowConfig.getName(), windowConfig.getSize()
                    );

                    MetricPoint primaryPoint = points.get(0);
                    return KeyValue.pair(primaryPoint.getId(), primaryPoint);
                })
                .filter((key, value) -> value != null);
    }

    private List<MetricPoint> createMetricPoints(
            AggregationKey key,
            AggregateValue aggregate,
            Window window,
            String windowName,
            Duration windowSize) {

        List<MetricPoint> points = new ArrayList<>();
        Instant windowStart = Instant.ofEpochMilli(window.start());
        Instant windowEnd = Instant.ofEpochMilli(window.end());

        MetricPoint countMetric = new MetricPoint(
                IdUtils.newMetricId(),
                "log_count",
                windowStart,
                windowEnd,
                (double) aggregate.count,
                MetricPoint.MetricType.COUNTER
        );
        countMetric.addTag("service", key.getServiceName());
        countMetric.addTag("level", key.getLevel() != null ? key.getLevel().name() : "UNKNOWN");
        countMetric.addTag("window", windowName);
        countMetric.addTag("error_code", key.getErrorCode());
        points.add(countMetric);

        MetricPoint errorRateMetric = new MetricPoint(
                IdUtils.newMetricId(),
                "error_rate",
                windowStart,
                windowEnd,
                aggregate.getErrorRate(),
                MetricPoint.MetricType.GAUGE
        );
        errorRateMetric.addTag("service", key.getServiceName());
        errorRateMetric.addTag("window", windowName);
        points.add(errorRateMetric);

        MetricPoint epsMetric = new MetricPoint(
                IdUtils.newMetricId(),
                "eps",
                windowStart,
                windowEnd,
                aggregate.getEps(windowSize),
                MetricPoint.MetricType.GAUGE
        );
        epsMetric.addTag("service", key.getServiceName());
        epsMetric.addTag("window", windowName);
        points.add(epsMetric);

        MetricPoint errorCountMetric = new MetricPoint(
                IdUtils.newMetricId(),
                "error_count",
                windowStart,
                windowEnd,
                (double) aggregate.errorCount,
                MetricPoint.MetricType.COUNTER
        );
        errorCountMetric.addTag("service", key.getServiceName());
        errorCountMetric.addTag("window", windowName);
        points.add(errorCountMetric);

        MetricPoint warnCountMetric = new MetricPoint(
                IdUtils.newMetricId(),
                "warn_count",
                windowStart,
                windowEnd,
                (double) aggregate.warnCount,
                MetricPoint.MetricType.COUNTER
        );
        warnCountMetric.addTag("service", key.getServiceName());
        warnCountMetric.addTag("window", windowName);
        points.add(warnCountMetric);

        MetricPoint bytesMetric = new MetricPoint(
                IdUtils.newMetricId(),
                "bytes_processed",
                windowStart,
                windowEnd,
                (double) aggregate.bytesProcessed,
                MetricPoint.MetricType.COUNTER
        );
        bytesMetric.addTag("service", key.getServiceName());
        bytesMetric.addTag("window", windowName);
        points.add(bytesMetric);

        MetricPoint uniquePatternsMetric = new MetricPoint(
                IdUtils.newMetricId(),
                "unique_patterns",
                windowStart,
                windowEnd,
                (double) aggregate.uniquePatterns.size(),
                MetricPoint.MetricType.GAUGE
        );
        uniquePatternsMetric.addTag("service", key.getServiceName());
        uniquePatternsMetric.addTag("window", windowName);
        points.add(uniquePatternsMetric);

        List<Map.Entry<String, Long>> topPatterns = aggregate.getTopPatterns(5);
        for (int i = 0; i < topPatterns.size(); i++) {
            Map.Entry<String, Long> entry = topPatterns.get(i);
            MetricPoint patternMetric = new MetricPoint(
                    IdUtils.newMetricId(),
                    "pattern_count_top" + (i + 1),
                    windowStart,
                    windowEnd,
                    (double) entry.getValue(),
                    MetricPoint.MetricType.COUNTER
            );
            patternMetric.addTag("service", key.getServiceName());
            patternMetric.addTag("pattern_id", entry.getKey());
            patternMetric.addTag("window", windowName);
            patternMetric.addTag("rank", String.valueOf(i + 1));
            points.add(patternMetric);
        }

        return points;
    }

    private final Map<String, List<MetricPoint>> recentMetrics = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_METRICS_PER_KEY = 100;

    public double getCurrentMetricValue(String metricName, String service, Duration window) {
        String key = service + "|" + metricName;
        List<MetricPoint> points = recentMetrics.getOrDefault(key, Collections.emptyList());
        Instant cutoff = Instant.now().minus(window);
        return points.stream()
                .filter(p -> p.getTimestamp().isAfter(cutoff))
                .mapToDouble(MetricPoint::getValue)
                .average()
                .orElse(0.0);
    }

    public double getPatternFrequency(String service, Duration window, List<LogLevel> levelFilter) {
        String key = service + "|log_count";
        List<MetricPoint> points = recentMetrics.getOrDefault(key, Collections.emptyList());
        Instant cutoff = Instant.now().minus(window);
        return points.stream()
                .filter(p -> p.getTimestamp().isAfter(cutoff))
                .mapToDouble(MetricPoint::getValue)
                .sum();
    }

    public double getBaselineFrequency(String service, Duration window) {
        return 10.0;
    }

    public double getErrorRate(String service, Duration window) {
        return getCurrentMetricValue("error_rate", service, window);
    }

    public void recordMetric(MetricPoint metric) {
        String service = metric.getTags().get("service");
        if (service == null) service = "unknown";
        String key = service + "|" + metric.getName();
        recentMetrics.compute(key, (k, list) -> {
            if (list == null) list = new ArrayList<>();
            list.add(metric);
            if (list.size() > MAX_METRICS_PER_KEY) {
                list = new ArrayList<>(list.subList(list.size() - MAX_METRICS_PER_KEY, list.size()));
            }
            return list;
        });
    }
}
