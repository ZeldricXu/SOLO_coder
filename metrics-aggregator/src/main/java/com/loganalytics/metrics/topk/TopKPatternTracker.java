package com.loganalytics.metrics.topk;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.MetricPoint;
import com.loganalytics.common.util.IdUtils;
import com.loganalytics.metrics.config.MetricsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TopKPatternTracker {
    private static final Logger log = LoggerFactory.getLogger(TopKPatternTracker.class);

    private final MetricsConfig config;
    private final Map<String, AtomicLong> globalPatternCounts;
    private final Map<String, Map<String, AtomicLong>> servicePatternCounts;
    private final Cache<String, String> patternTemplateCache;
    private volatile long lastUpdateTime;

    public TopKPatternTracker(MetricsConfig config) {
        this.config = config;
        this.globalPatternCounts = new ConcurrentHashMap<>();
        this.servicePatternCounts = new ConcurrentHashMap<>();
        this.patternTemplateCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public void process(LogEvent event) {
        if (event.getPatternId() == null) return;

        String patternId = event.getPatternId();
        String serviceName = event.getServiceName() != null ? event.getServiceName() : "unknown";

        globalPatternCounts.computeIfAbsent(patternId, k -> new AtomicLong(0))
                .incrementAndGet();

        servicePatternCounts.computeIfAbsent(serviceName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(patternId, k -> new AtomicLong(0))
                .incrementAndGet();

        if (event.getPatternTemplate() != null) {
            patternTemplateCache.put(patternId, event.getPatternTemplate());
        }
    }

    public List<MetricPoint> getTopKMetrics() {
        List<MetricPoint> metrics = new ArrayList<>();
        Instant now = Instant.now();
        Instant windowStart = Instant.ofEpochMilli(lastUpdateTime);

        List<Map.Entry<String, Long>> globalTopK = getGlobalTopK();
        for (int i = 0; i < globalTopK.size(); i++) {
            Map.Entry<String, Long> entry = globalTopK.get(i);
            MetricPoint metric = createTopKMetric(
                    "global_top_" + (i + 1),
                    entry.getKey(),
                    entry.getValue(),
                    i + 1,
                    null,
                    windowStart,
                    now
            );
            metrics.add(metric);
        }

        for (Map.Entry<String, Map<String, AtomicLong>> serviceEntry : servicePatternCounts.entrySet()) {
            String serviceName = serviceEntry.getKey();
            List<Map.Entry<String, Long>> serviceTopK = getServiceTopK(serviceName, config.getTopKSize());

            for (int i = 0; i < serviceTopK.size(); i++) {
                Map.Entry<String, Long> entry = serviceTopK.get(i);
                MetricPoint metric = createTopKMetric(
                        "service_top_" + (i + 1),
                        entry.getKey(),
                        entry.getValue(),
                        i + 1,
                        serviceName,
                        windowStart,
                        now
                );
                metrics.add(metric);
            }
        }

        lastUpdateTime = System.currentTimeMillis();
        globalPatternCounts.clear();
        servicePatternCounts.clear();

        return metrics;
    }

    private List<Map.Entry<String, Long>> getGlobalTopK() {
        return globalPatternCounts.entrySet().stream()
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().get()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(config.getTopKSize())
                .toList();
    }

    private List<Map.Entry<String, Long>> getServiceTopK(String serviceName, int k) {
        Map<String, AtomicLong> patternCounts = servicePatternCounts.get(serviceName);
        if (patternCounts == null || patternCounts.isEmpty()) {
            return Collections.emptyList();
        }

        return patternCounts.entrySet().stream()
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().get()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(k)
                .toList();
    }

    private MetricPoint createTopKMetric(
            String metricName,
            String patternId,
            long count,
            int rank,
            String serviceName,
            Instant windowStart,
            Instant windowEnd) {

        MetricPoint metric = new MetricPoint(
                IdUtils.newMetricId(),
                metricName,
                windowStart,
                windowEnd,
                (double) count,
                MetricPoint.MetricType.GAUGE
        );

        metric.addTag("pattern_id", patternId);
        metric.addTag("rank", String.valueOf(rank));
        metric.addTag("window", "topk_" + config.getTopKUpdateInterval().getSeconds() + "s");

        String template = patternTemplateCache.getIfPresent(patternId);
        if (template != null) {
            metric.addTag("pattern_template", template.length() > 100 ?
                    template.substring(0, 100) : template);
        }

        if (serviceName != null) {
            metric.addTag("service", serviceName);
        }

        return metric;
    }

    public Map<String, Object> getDiagnostics() {
        int trackedPatterns = globalPatternCounts.size();
        int trackedServices = servicePatternCounts.size();
        int cachedTemplates = patternTemplateCache.estimatedSize();

        return Map.of(
                "trackedPatterns", trackedPatterns,
                "trackedServices", trackedServices,
                "cachedTemplates", cachedTemplates,
                "topKSize", config.getTopKSize(),
                "updateIntervalSeconds", config.getTopKUpdateInterval().getSeconds()
        );
    }

    public List<Map<String, Object>> getCurrentTopKWithDetails() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map.Entry<String, Long>> topK = getGlobalTopK();

        for (int i = 0; i < topK.size(); i++) {
            Map.Entry<String, Long> entry = topK.get(i);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("rank", i + 1);
            detail.put("patternId", entry.getKey());
            detail.put("count", entry.getValue());
            detail.put("template", patternTemplateCache.getIfPresent(entry.getKey()));
            result.add(detail);
        }

        return result;
    }
}
