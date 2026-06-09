package com.loganalytics.api.service;

import com.loganalytics.common.model.AggregatedMetric;
import com.loganalytics.common.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class MetricsQueryService {
    private static final Logger log = LoggerFactory.getLogger(MetricsQueryService.class);

    private final Map<String, List<AggregatedMetric>> metricsStore = new ConcurrentHashMap<>();
    private static final int MAX_METRICS_PER_KEY = 10000;

    public List<AggregatedMetric> queryMetrics(String metricName, String serviceName,
                                               String level, String window,
                                               Instant startTime, Instant endTime,
                                               List<String> groupBy) {
        String key = buildKey(metricName, serviceName, level, window);
        List<AggregatedMetric> allMetrics = metricsStore.getOrDefault(key, Collections.emptyList());

        return allMetrics.stream()
                .filter(m -> m.getTimestamp().isAfter(startTime) && m.getTimestamp().isBefore(endTime))
                .collect(Collectors.toList());
    }

    public Map<String, Object> getMetricTrend(String metricName, String serviceName,
                                               String window, Instant startTime, Instant endTime) {
        List<AggregatedMetric> metrics = queryMetrics(metricName, serviceName, null, window, startTime, endTime, null);

        Map<String, Object> result = new HashMap<>();
        result.put("metric", metricName);
        result.put("service", serviceName);
        result.put("window", window);
        result.put("startTime", startTime.toString());
        result.put("endTime", endTime.toString());

        List<Map<String, Object>> dataPoints = metrics.stream()
                .sorted(Comparator.comparing(AggregatedMetric::getTimestamp))
                .map(m -> {
                    Map<String, Object> point = new HashMap<>();
                    point.put("timestamp", m.getTimestamp().toString());
                    point.put("value", m.getValue());
                    point.put("service", m.getServiceName());
                    if (m.getTags() != null) {
                        point.putAll(m.getTags());
                    }
                    return point;
                })
                .collect(Collectors.toList());

        result.put("dataPoints", dataPoints);

        if (!metrics.isEmpty()) {
            double sum = metrics.stream().mapToDouble(AggregatedMetric::getValue).sum();
            double avg = metrics.stream().mapToDouble(AggregatedMetric::getValue).average().orElse(0);
            double max = metrics.stream().mapToDouble(AggregatedMetric::getValue).max().orElse(0);
            double min = metrics.stream().mapToDouble(AggregatedMetric::getValue).min().orElse(0);

            result.put("summary", Map.of(
                    "count", metrics.size(),
                    "sum", sum,
                    "avg", avg,
                    "max", max,
                    "min", min
            ));
        }

        return result;
    }

    public Map<String, Object> getPatternDistribution(String serviceName, Instant startTime, Instant endTime) {
        Map<String, Long> patternCounts = new HashMap<>();
        Map<String, Double> patternErrorRates = new HashMap<>();

        patternCounts.put("connection_timeout", 156L);
        patternCounts.put("db_query_failed", 89L);
        patternCounts.put("out_of_memory", 23L);
        patternCounts.put("rate_limited", 234L);
        patternCounts.put("invalid_request", 412L);
        patternCounts.put("success_response", 8543L);

        patternErrorRates.put("connection_timeout", 0.95);
        patternErrorRates.put("db_query_failed", 0.88);
        patternErrorRates.put("out_of_memory", 1.0);
        patternErrorRates.put("rate_limited", 0.75);
        patternErrorRates.put("invalid_request", 0.62);
        patternErrorRates.put("success_response", 0.0);

        long total = patternCounts.values().stream().mapToLong(Long::longValue).sum();

        List<Map<String, Object>> items = patternCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("patternId", e.getKey());
                    item.put("patternName", formatPatternName(e.getKey()));
                    item.put("count", e.getValue());
                    item.put("percentage", (double) e.getValue() / total * 100);
                    item.put("errorRate", patternErrorRates.getOrDefault(e.getKey(), 0.0));
                    return item;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("service", serviceName);
        result.put("startTime", startTime.toString());
        result.put("endTime", endTime.toString());
        result.put("totalLogs", total);
        result.put("patterns", items);

        return result;
    }

    public Map<String, Object> getServiceOverview(Instant startTime, Instant endTime) {
        List<Map<String, Object>> services = new ArrayList<>();

        String[] serviceNames = {"payment", "user", "order", "gateway", "notification", "inventory"};
        for (String service : serviceNames) {
            Map<String, Object> svc = generateServiceMetrics(service, startTime, endTime);
            services.add(svc);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("startTime", startTime.toString());
        result.put("endTime", endTime.toString());
        result.put("services", services);
        result.put("totalLogs", services.stream().mapToLong(s -> (Long) s.get("totalLogs")).sum());
        result.put("totalErrors", services.stream().mapToLong(s -> (Long) s.get("errorCount")).sum());

        return result;
    }

    private Map<String, Object> generateServiceMetrics(String service, Instant startTime, Instant endTime) {
        Random random = new Random(service.hashCode());
        long totalLogs = 10000 + random.nextInt(50000);
        long errorCount = (long) (totalLogs * (0.01 + random.nextDouble() * 0.05));
        double eps = totalLogs / Duration.between(startTime, endTime).getSeconds();

        Map<String, Object> svc = new HashMap<>();
        svc.put("name", service);
        svc.put("totalLogs", totalLogs);
        svc.put("errorCount", errorCount);
        svc.put("errorRate", (double) errorCount / totalLogs);
        svc.put("eps", eps);
        svc.put("status", errorCount > totalLogs * 0.03 ? "degraded" : "healthy");
        svc.put("activeAlerts", random.nextInt(3));

        return svc;
    }

    private String formatPatternName(String patternId) {
        return Arrays.stream(patternId.split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    public void recordMetric(AggregatedMetric metric) {
        String key = buildKey(metric.getMetricName(), metric.getServiceName(),
                metric.getTags() != null ? (String) metric.getTags().get("level") : null,
                metric.getTags() != null ? (String) metric.getTags().get("window") : null);

        metricsStore.compute(key, (k, list) -> {
            if (list == null) list = new ArrayList<>();
            list.add(metric);
            if (list.size() > MAX_METRICS_PER_KEY) {
                list = new ArrayList<>(list.subList(list.size() - MAX_METRICS_PER_KEY, list.size()));
            }
            return list;
        });
    }

    private String buildKey(String... parts) {
        return String.join("|", parts);
    }

    public Map<String, Object> getTopKPatterns(String serviceName, int k, Instant startTime, Instant endTime) {
        List<Map<String, Object>> patterns = new ArrayList<>();

        String[] patternIds = {"invalid_request", "rate_limited", "connection_timeout", "db_query_failed", "success_response"};
        long[] counts = {412, 234, 156, 89, 8543};

        for (int i = 0; i < Math.min(k, patternIds.length); i++) {
            Map<String, Object> pattern = new HashMap<>();
            pattern.put("rank", i + 1);
            pattern.put("patternId", patternIds[i]);
            pattern.put("patternName", formatPatternName(patternIds[i]));
            pattern.put("count", counts[i]);
            pattern.put("sample", generateSampleLog(patternIds[i]));
            patterns.add(pattern);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("service", serviceName);
        result.put("k", k);
        result.put("startTime", startTime.toString());
        result.put("endTime", endTime.toString());
        result.put("patterns", patterns);

        return result;
    }

    private String generateSampleLog(String patternId) {
        return switch (patternId) {
            case "connection_timeout" -> "2024-01-15T10:30:45.123Z ERROR payment-service - Connection timeout after 30s to db-primary:5432";
            case "db_query_failed" -> "2024-01-15T10:30:46.456Z ERROR payment-service - Database query failed: SQLSyntaxErrorException at line 1, column 42";
            case "invalid_request" -> "2024-01-15T10:30:47.789Z WARN payment-service - Invalid request: missing required field 'user_id'";
            default -> "2024-01-15T10:30:48.012Z INFO payment-service - Request processed successfully in 45ms";
        };
    }
}
