package com.enterprise.risk.observability.controller;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.observability.metrics.AlertEfficiencyTracker;
import com.enterprise.risk.observability.metrics.MetricsCollector;
import com.enterprise.risk.observability.metrics.ModelDriftMonitor;
import com.enterprise.risk.observability.metrics.RuleHitRateTracker;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RDeque;
import org.redisson.api.RedissonClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营大屏API控制器
 * 提供实时统计概览、规则命中率排行、实时告警列表、模型性能指标、告警处理效率等接口
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private static final String RECENT_ALERTS_KEY = "risk:alerts:recent";
    private static final String BUSINESS_LINE_COUNTER_KEY = "risk:stats:businessline:";

    private final MetricsCollector metricsCollector;
    private final RuleHitRateTracker ruleHitRateTracker;
    private final ModelDriftMonitor modelDriftMonitor;
    private final AlertEfficiencyTracker alertEfficiencyTracker;
    private final RedissonClient redissonClient;

    /**
     * 实时统计概览
     * 返回今日事件数、告警数、拦截数、各业务线分布
     */
    @GetMapping("/summary")
    public ResponseEntity<DashboardSummary> getSummary() {
        DashboardSummary summary = DashboardSummary.builder()
                .timestamp(System.currentTimeMillis())
                .build();

        summary.setActiveAlerts(metricsCollector.getActiveAlertCount());
        summary.setPendingReviews(metricsCollector.getPendingReviewCount());
        summary.setEventQps(metricsCollector.getEventQPS());
        summary.setEventProcessingP95Ms(metricsCollector.getEventProcessingP95Ms());
        summary.setEventProcessingP99Ms(metricsCollector.getEventProcessingP99Ms());

        Map<String, Long> businessLineEvents = new HashMap<>();
        Map<String, Long> businessLineAlerts = new HashMap<>();
        Map<String, Long> businessLineBlocks = new HashMap<>();

        String todayKey = getTodayKey();
        loadBusinessLineStats(BUSINESS_LINE_COUNTER_KEY + "events:" + todayKey, businessLineEvents);
        loadBusinessLineStats(BUSINESS_LINE_COUNTER_KEY + "alerts:" + todayKey, businessLineAlerts);
        loadBusinessLineStats(BUSINESS_LINE_COUNTER_KEY + "blocks:" + todayKey, businessLineBlocks);

        summary.setTodayEvents(sumMapValues(businessLineEvents));
        summary.setTodayAlerts(sumMapValues(businessLineAlerts));
        summary.setTodayBlocks(sumMapValues(businessLineBlocks));
        summary.setBusinessLineEventDistribution(businessLineEvents);
        summary.setBusinessLineAlertDistribution(businessLineAlerts);
        summary.setBusinessLineBlockDistribution(businessLineBlocks);

        summary.setYesterdayTrendEvents(calculateTrend(BUSINESS_LINE_COUNTER_KEY + "events:", 1));
        summary.setYesterdayTrendAlerts(calculateTrend(BUSINESS_LINE_COUNTER_KEY + "alerts:", 1));
        summary.setYesterdayTrendBlocks(calculateTrend(BUSINESS_LINE_COUNTER_KEY + "blocks:", 1));

        return ResponseEntity.ok(summary);
    }

    /**
     * 规则命中率排行
     */
    @GetMapping("/rules")
    public ResponseEntity<RuleRankingResponse> getRuleRanking(
            @RequestParam(defaultValue = "1d") String window,
            @RequestParam(defaultValue = "20") int topN) {
        String normalizedWindow = normalizeWindow(window);
        List<RuleHitRateTracker.RuleHitRate> rankings = ruleHitRateTracker.getRuleHitRateRanking(topN, normalizedWindow);

        RuleRankingResponse response = RuleRankingResponse.builder()
                .window(normalizedWindow)
                .topN(topN)
                .totalRules(rankings.size())
                .rankings(rankings)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 实时告警列表（分页、过滤）
     */
    @GetMapping("/alerts")
    public ResponseEntity<AlertListResponse> getRecentAlerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String businessLine,
            @RequestParam(required = false) String status) {

        RDeque<AlertEvent> recentAlerts = redissonClient.getDeque(RECENT_ALERTS_KEY);
        List<AlertEvent> allAlerts = new ArrayList<>(recentAlerts);

        List<AlertEvent> filtered = new ArrayList<>();
        for (AlertEvent alert : allAlerts) {
            if (severity != null && !severity.isEmpty()) {
                if (alert.getSeverity() == null || !severity.equalsIgnoreCase(alert.getSeverity().name())) {
                    continue;
                }
            }
            if (businessLine != null && !businessLine.isEmpty()) {
                if (!businessLine.equals(alert.getBusinessLine())) {
                    continue;
                }
            }
            if (status != null && !status.isEmpty()) {
                if (alert.getStatus() == null || !status.equalsIgnoreCase(alert.getStatus().name())) {
                    continue;
                }
            }
            filtered.add(alert);
        }

        int total = filtered.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<AlertEvent> paged = filtered.subList(fromIndex, toIndex);

        AlertListResponse response = AlertListResponse.builder()
                .page(page)
                .size(size)
                .total(total)
                .totalPages((int) Math.ceil((double) total / size))
                .alerts(paged)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 模型性能指标
     */
    @GetMapping("/model")
    public ResponseEntity<ModelPerformanceResponse> getModelPerformance(
            @RequestParam(defaultValue = "default") String modelId,
            @RequestParam(defaultValue = "v1") String modelVersion) {

        ModelDriftMonitor.ModelPerformance performance = modelDriftMonitor.calculatePerformance(modelId, modelVersion);
        ModelDriftMonitor.DriftDetectionResult driftResult = modelDriftMonitor.detectPerformanceDrift(modelId, modelVersion);
        Map<String, Double> featureDrift = modelDriftMonitor.detectFeatureDrift(modelId, modelVersion);

        ModelPerformanceResponse response = ModelPerformanceResponse.builder()
                .modelId(modelId)
                .modelVersion(modelVersion)
                .performance(performance)
                .driftResult(driftResult)
                .featureDriftScores(featureDrift)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 告警处理效率指标
     */
    @GetMapping("/efficiency")
    public ResponseEntity<EfficiencyResponse> getEfficiency(
            @RequestParam(required = false) String businessLine,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int operatorTopN) {

        AlertEfficiencyTracker.EfficiencyMetrics overallMetrics =
                alertEfficiencyTracker.getEfficiencyMetrics(businessLine, days);

        List<AlertEfficiencyTracker.OperatorEfficiency> operatorRanking =
                alertEfficiencyTracker.getOperatorEfficiencyRanking(days, operatorTopN);

        EfficiencyResponse response = EfficiencyResponse.builder()
                .businessLine(businessLine)
                .days(days)
                .overallMetrics(overallMetrics)
                .operatorRanking(operatorRanking)
                .build();

        return ResponseEntity.ok(response);
    }

    private String normalizeWindow(String window) {
        return switch (window.toLowerCase()) {
            case "5m", "5min", "5minutes" -> "5m";
            case "1h", "1hour" -> "1h";
            case "1d", "1day", "24h" -> "1d";
            default -> "1d";
        };
    }

    private String getTodayKey() {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @SuppressWarnings("unchecked")
    private void loadBusinessLineStats(String key, Map<String, Long> target) {
        Map<Object, Object> map = redissonClient.getMap(key);
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            String bl = (String) entry.getKey();
            Long count = entry.getValue() instanceof Long ? (Long) entry.getValue()
                    : ((Number) entry.getValue()).longValue();
            target.put(bl, count);
        }
    }

    private long sumMapValues(Map<String, Long> map) {
        return map.values().stream().mapToLong(Long::longValue).sum();
    }

    private Double calculateTrend(String prefix, int daysAgo) {
        String todayKey = getTodayKey();
        String yesterdayKey = java.time.LocalDate.now()
                .minusDays(daysAgo)
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        long today = sumMapFromKey(prefix + todayKey);
        long yesterday = sumMapFromKey(prefix + yesterdayKey);

        if (yesterday == 0) return today > 0 ? 100.0 : 0.0;
        return ((double) (today - yesterday) / yesterday) * 100;
    }

    @SuppressWarnings("unchecked")
    private long sumMapFromKey(String key) {
        long sum = 0;
        Map<Object, Object> map = redissonClient.getMap(key);
        for (Object value : map.values()) {
            sum += value instanceof Long ? (Long) value : ((Number) value).longValue();
        }
        return sum;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardSummary implements Serializable {
        private Long timestamp;
        private Long todayEvents;
        private Long todayAlerts;
        private Long todayBlocks;
        private Long activeAlerts;
        private Long pendingReviews;
        private Double eventQps;
        private Double eventProcessingP95Ms;
        private Double eventProcessingP99Ms;
        private Double yesterdayTrendEvents;
        private Double yesterdayTrendAlerts;
        private Double yesterdayTrendBlocks;
        private Map<String, Long> businessLineEventDistribution;
        private Map<String, Long> businessLineAlertDistribution;
        private Map<String, Long> businessLineBlockDistribution;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleRankingResponse implements Serializable {
        private String window;
        private Integer topN;
        private Integer totalRules;
        private List<RuleHitRateTracker.RuleHitRate> rankings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertListResponse implements Serializable {
        private Integer page;
        private Integer size;
        private Integer total;
        private Integer totalPages;
        private List<AlertEvent> alerts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelPerformanceResponse implements Serializable {
        private String modelId;
        private String modelVersion;
        private ModelDriftMonitor.ModelPerformance performance;
        private ModelDriftMonitor.DriftDetectionResult driftResult;
        private Map<String, Double> featureDriftScores;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EfficiencyResponse implements Serializable {
        private String businessLine;
        private Integer days;
        private AlertEfficiencyTracker.EfficiencyMetrics overallMetrics;
        private List<AlertEfficiencyTracker.OperatorEfficiency> operatorRanking;
    }
}
