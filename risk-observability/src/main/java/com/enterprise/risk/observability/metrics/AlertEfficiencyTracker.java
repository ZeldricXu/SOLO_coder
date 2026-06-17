package com.enterprise.risk.observability.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 告警处理效率跟踪
 * 跟踪告警响应时间、解决时间、误报率统计（误报需人工标记）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEfficiencyTracker {

    private static final String ALERT_LIFECYCLE_KEY = "risk:alert_lifecycle:";
    private static final String DAILY_EFFICIENCY_KEY = "risk:efficiency:daily:";
    private static final String OPERATOR_STATS_KEY = "risk:efficiency:operator:";
    private static final String ALERT_STATS_PREFIX = "risk:stats:alerts:";

    private final RedissonClient redissonClient;

    /**
     * 告警生命周期状态缓存
     */
    private final Map<String, AlertLifecycle> lifecycleCache = new ConcurrentHashMap<>();

    /**
     * 记录告警创建
     */
    public void recordAlertCreated(String alertId, String ruleId, String businessLine,
                                   String severity, String operatorId) {
        AlertLifecycle lifecycle = AlertLifecycle.builder()
                .alertId(alertId)
                .ruleId(ruleId)
                .businessLine(businessLine)
                .severity(severity)
                .assignedOperator(operatorId)
                .createdAt(System.currentTimeMillis())
                .status(AlertLifecycle.AlertStatus.OPEN)
                .build();

        lifecycleCache.put(alertId, lifecycle);
        saveLifecycle(lifecycle);

        incrementCounter(ALERT_STATS_PREFIX + "created:" + getDateKey(), businessLine, 1);
        if (operatorId != null) {
            incrementCounter(OPERATOR_STATS_KEY + "assigned:" + getDateKey(), operatorId, 1);
        }
    }

    /**
     * 记录告警响应（首次查看/认领）
     */
    public void recordAlertAcknowledged(String alertId, String operatorId) {
        AlertLifecycle lifecycle = getOrLoadLifecycle(alertId);
        if (lifecycle == null) return;

        long now = System.currentTimeMillis();
        lifecycle.setAcknowledgedAt(now);
        lifecycle.setAcknowledgedBy(operatorId);
        lifecycle.setResponseTimeMs(now - lifecycle.getCreatedAt());
        lifecycle.setStatus(AlertLifecycle.AlertStatus.IN_PROGRESS);
        saveLifecycle(lifecycle);

        if (operatorId != null) {
            incrementCounter(OPERATOR_STATS_KEY + "acknowledged:" + getDateKey(), operatorId, 1);
            addValueToList(OPERATOR_STATS_KEY + "response_times:" + getDateKey(), operatorId, lifecycle.getResponseTimeMs());
        }
    }

    /**
     * 记录告警解决
     */
    public void recordAlertResolved(String alertId, String operatorId, boolean isFalsePositive,
                                    String resolutionNote) {
        AlertLifecycle lifecycle = getOrLoadLifecycle(alertId);
        if (lifecycle == null) return;

        long now = System.currentTimeMillis();
        lifecycle.setResolvedAt(now);
        lifecycle.setResolvedBy(operatorId);
        lifecycle.setResolutionTimeMs(now - lifecycle.getCreatedAt());
        lifecycle.setHandleTimeMs(lifecycle.getAcknowledgedAt() != null
                ? now - lifecycle.getAcknowledgedAt() : null);
        lifecycle.setFalsePositive(isFalsePositive);
        lifecycle.setResolutionNote(resolutionNote);
        lifecycle.setStatus(isFalsePositive ? AlertLifecycle.AlertStatus.FALSE_POSITIVE : AlertLifecycle.AlertStatus.RESOLVED);
        saveLifecycle(lifecycle);

        String dateKey = getDateKey();
        String businessLine = lifecycle.getBusinessLine();

        incrementCounter(ALERT_STATS_PREFIX + "resolved:" + dateKey, businessLine, 1);
        addValueToList(ALERT_STATS_PREFIX + "resolution_times:" + dateKey, businessLine, lifecycle.getResolutionTimeMs());

        if (lifecycle.getResponseTimeMs() != null) {
            addValueToList(ALERT_STATS_PREFIX + "response_times:" + dateKey, businessLine, lifecycle.getResponseTimeMs());
        }
        if (isFalsePositive) {
            incrementCounter(ALERT_STATS_PREFIX + "false_positive:" + dateKey, businessLine, 1);
        }
        if (lifecycle.getHandleTimeMs() != null) {
            addValueToList(ALERT_STATS_PREFIX + "handle_times:" + dateKey, businessLine, lifecycle.getHandleTimeMs());
        }

        if (operatorId != null) {
            incrementCounter(OPERATOR_STATS_KEY + "resolved:" + dateKey, operatorId, 1);
            addValueToList(OPERATOR_STATS_KEY + "resolution_times:" + dateKey, operatorId, lifecycle.getResolutionTimeMs());
            if (isFalsePositive) {
                incrementCounter(OPERATOR_STATS_KEY + "false_positive:" + dateKey, operatorId, 1);
            }
        }

        lifecycleCache.remove(alertId);
    }

    /**
     * 获取指定时间范围的效率指标
     */
    public EfficiencyMetrics getEfficiencyMetrics(String businessLine, int days) {
        long totalCreated = 0;
        long totalResolved = 0;
        long totalFalsePositive = 0;
        List<Long> allResponseTimes = new ArrayList<>();
        List<Long> allResolutionTimes = new ArrayList<>();
        List<Long> allHandleTimes = new ArrayList<>();

        for (int i = 0; i < days; i++) {
            String dateKey = getDateKey(i);
            totalCreated += getCount(ALERT_STATS_PREFIX + "created:" + dateKey, businessLine);
            totalResolved += getCount(ALERT_STATS_PREFIX + "resolved:" + dateKey, businessLine);
            totalFalsePositive += getCount(ALERT_STATS_PREFIX + "false_positive:" + dateKey, businessLine);
            allResponseTimes.addAll(getValueList(ALERT_STATS_PREFIX + "response_times:" + dateKey, businessLine));
            allResolutionTimes.addAll(getValueList(ALERT_STATS_PREFIX + "resolution_times:" + dateKey, businessLine));
            allHandleTimes.addAll(getValueList(ALERT_STATS_PREFIX + "handle_times:" + dateKey, businessLine));
        }

        double avgResponseTime = averageMs(allResponseTimes);
        double avgResolutionTime = averageMs(allResolutionTimes);
        double avgHandleTime = averageMs(allHandleTimes);
        double p95ResponseTime = percentileMs(allResponseTimes, 0.95);
        double p95ResolutionTime = percentileMs(allResolutionTimes, 0.95);
        double falsePositiveRate = totalResolved > 0 ? (double) totalFalsePositive / totalResolved : 0.0;

        return EfficiencyMetrics.builder()
                .businessLine(businessLine)
                .days(days)
                .totalCreated(totalCreated)
                .totalResolved(totalResolved)
                .totalFalsePositive(totalFalsePositive)
                .avgResponseTimeMs(avgResponseTime)
                .avgResolutionTimeMs(avgResolutionTime)
                .avgHandleTimeMs(avgHandleTime)
                .p95ResponseTimeMs(p95ResponseTime)
                .p95ResolutionTimeMs(p95ResolutionTime)
                .falsePositiveRate(falsePositiveRate)
                .build();
    }

    /**
     * 获取运营人员效率排行
     */
    public List<OperatorEfficiency> getOperatorEfficiencyRanking(int days, int topN) {
        Map<String, OperatorEfficiency> operatorStats = new HashMap<>();

        for (int i = 0; i < days; i++) {
            String dateKey = getDateKey(i);

            RMap<String, Long> assignedMap = redissonClient.getMap(OPERATOR_STATS_KEY + "assigned:" + dateKey);
            for (Map.Entry<String, Long> entry : assignedMap.entrySet()) {
                String op = entry.getKey();
                OperatorEfficiency stat = operatorStats.computeIfAbsent(op,
                        k -> new OperatorEfficiency(op, 0L, 0L, 0L, 0L,
                                new ArrayList<>(), new ArrayList<>()));
                stat.setTotalAssigned(stat.getTotalAssigned() + entry.getValue());
            }

            RMap<String, Long> resolvedMap = redissonClient.getMap(OPERATOR_STATS_KEY + "resolved:" + dateKey);
            for (Map.Entry<String, Long> entry : resolvedMap.entrySet()) {
                String op = entry.getKey();
                OperatorEfficiency stat = operatorStats.computeIfAbsent(op,
                        k -> new OperatorEfficiency(op, 0L, 0L, 0L, 0L,
                                new ArrayList<>(), new ArrayList<>()));
                stat.setTotalResolved(stat.getTotalResolved() + entry.getValue());
            }

            RMap<String, Long> fpMap = redissonClient.getMap(OPERATOR_STATS_KEY + "false_positive:" + dateKey);
            for (Map.Entry<String, Long> entry : fpMap.entrySet()) {
                String op = entry.getKey();
                OperatorEfficiency stat = operatorStats.computeIfAbsent(op,
                        k -> new OperatorEfficiency(op, 0L, 0L, 0L, 0L,
                                new ArrayList<>(), new ArrayList<>()));
                stat.setTotalFalsePositive(stat.getTotalFalsePositive() + entry.getValue());
            }

            RMap<String, List<Long>> rtMap = redissonClient.getMap(OPERATOR_STATS_KEY + "response_times:" + dateKey);
            for (Map.Entry<String, List<Long>> entry : rtMap.entrySet()) {
                String op = entry.getKey();
                OperatorEfficiency stat = operatorStats.computeIfAbsent(op,
                        k -> new OperatorEfficiency(op, 0L, 0L, 0L, 0L,
                                new ArrayList<>(), new ArrayList<>()));
                stat.getResponseTimes().addAll(entry.getValue());
            }

            RMap<String, List<Long>> resMap = redissonClient.getMap(OPERATOR_STATS_KEY + "resolution_times:" + dateKey);
            for (Map.Entry<String, List<Long>> entry : resMap.entrySet()) {
                String op = entry.getKey();
                OperatorEfficiency stat = operatorStats.computeIfAbsent(op,
                        k -> new OperatorEfficiency(op, 0L, 0L, 0L, 0L,
                                new ArrayList<>(), new ArrayList<>()));
                stat.getResolutionTimes().addAll(entry.getValue());
            }
        }

        List<OperatorEfficiency> result = new ArrayList<>();
        for (OperatorEfficiency stat : operatorStats.values()) {
            stat.calculateMetrics();
            result.add(stat);
        }

        result.sort(Comparator.comparing(OperatorEfficiency::getEfficiencyScore).reversed());
        return result.subList(0, Math.min(topN, result.size()));
    }

    private AlertLifecycle getOrLoadLifecycle(String alertId) {
        AlertLifecycle cached = lifecycleCache.get(alertId);
        if (cached != null) return cached;

        Object loaded = redissonClient.getBucket(ALERT_LIFECYCLE_KEY + alertId).get();
        if (loaded instanceof AlertLifecycle) {
            lifecycleCache.put(alertId, (AlertLifecycle) loaded);
            return (AlertLifecycle) loaded;
        }
        return null;
    }

    private void saveLifecycle(AlertLifecycle lifecycle) {
        redissonClient.getBucket(ALERT_LIFECYCLE_KEY + lifecycle.getAlertId())
                .set(lifecycle, 30, TimeUnit.DAYS);
    }

    private void incrementCounter(String key, String field, long delta) {
        RMap<String, Long> map = redissonClient.getMap(key);
        map.addAndGet(field == null ? "__total__" : field, delta);
        map.expire(90, TimeUnit.DAYS);
    }

    private long getCount(String key, String field) {
        RMap<String, Long> map = redissonClient.getMap(key);
        Long value = map.get(field == null ? "__total__" : field);
        return value != null ? value : 0L;
    }

    @SuppressWarnings("unchecked")
    private void addValueToList(String key, String field, long value) {
        RMap<String, List<Long>> map = redissonClient.getMap(key);
        List<Long> list = map.computeIfAbsent(field == null ? "__total__" : field, k -> new ArrayList<>());
        list.add(value);
        if (list.size() > 10000) {
            list = new ArrayList<>(list.subList(list.size() - 5000, list.size()));
        }
        map.put(field == null ? "__total__" : field, list);
        map.expire(90, TimeUnit.DAYS);
    }

    @SuppressWarnings("unchecked")
    private List<Long> getValueList(String key, String field) {
        RMap<String, List<Long>> map = redissonClient.getMap(key);
        List<Long> list = map.get(field == null ? "__total__" : field);
        return list != null ? list : new ArrayList<>();
    }

    private double averageMs(List<Long> values) {
        if (values == null || values.isEmpty()) return 0.0;
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private double percentileMs(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private String getDateKey() {
        return getDateKey(0);
    }

    private String getDateKey(int daysAgo) {
        return LocalDate.ofInstant(Instant.now().minus(Duration.ofDays(daysAgo)),
                ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertLifecycle implements Serializable {
        private String alertId;
        private String ruleId;
        private String businessLine;
        private String severity;
        private String assignedOperator;
        private Long createdAt;
        private Long acknowledgedAt;
        private String acknowledgedBy;
        private Long resolvedAt;
        private String resolvedBy;
        private Long responseTimeMs;
        private Long resolutionTimeMs;
        private Long handleTimeMs;
        private Boolean falsePositive;
        private String resolutionNote;
        private AlertStatus status;

        public enum AlertStatus {
            OPEN, IN_PROGRESS, RESOLVED, FALSE_POSITIVE, ESCALATED
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EfficiencyMetrics implements Serializable {
        private String businessLine;
        private int days;
        private Long totalCreated;
        private Long totalResolved;
        private Long totalFalsePositive;
        private Double avgResponseTimeMs;
        private Double avgResolutionTimeMs;
        private Double avgHandleTimeMs;
        private Double p95ResponseTimeMs;
        private Double p95ResolutionTimeMs;
        private Double falsePositiveRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperatorEfficiency implements Serializable {
        private String operatorId;
        private String operatorName;
        private Long totalAssigned;
        private Long totalResolved;
        private Long totalFalsePositive;
        private Double avgResponseTimeMs;
        private Double avgResolutionTimeMs;
        private Double falsePositiveRate;
        private Double efficiencyScore;
        private List<Long> responseTimes;
        private List<Long> resolutionTimes;

        public OperatorEfficiency(String operatorId, long totalAssigned, long totalResolved,
                                   long totalFalsePositive, long ignored,
                                   List<Long> responseTimes, List<Long> resolutionTimes) {
            this.operatorId = operatorId;
            this.totalAssigned = totalAssigned;
            this.totalResolved = totalResolved;
            this.totalFalsePositive = totalFalsePositive;
            this.responseTimes = responseTimes;
            this.resolutionTimes = resolutionTimes;
        }

        public void calculateMetrics() {
            this.avgResponseTimeMs = responseTimes != null && !responseTimes.isEmpty()
                    ? responseTimes.stream().mapToLong(Long::longValue).average().orElse(0.0) : 0.0;
            this.avgResolutionTimeMs = resolutionTimes != null && !resolutionTimes.isEmpty()
                    ? resolutionTimes.stream().mapToLong(Long::longValue).average().orElse(0.0) : 0.0;
            this.falsePositiveRate = totalResolved != null && totalResolved > 0
                    ? (double) totalFalsePositive / totalResolved : 0.0;

            double resolveScore = totalResolved != null ? totalResolved * 1.0 : 0.0;
            double speedScore = this.avgResolutionTimeMs > 0
                    ? Math.max(0, 100000 - this.avgResolutionTimeMs) / 1000.0 : 0.0;
            double qualityScore = (1.0 - this.falsePositiveRate) * 100.0;
            this.efficiencyScore = resolveScore * 0.4 + speedScore * 0.3 + qualityScore * 0.3;
        }
    }
}
