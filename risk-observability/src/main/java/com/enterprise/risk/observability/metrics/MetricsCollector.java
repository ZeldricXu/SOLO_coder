package com.enterprise.risk.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer指标收集器
 * 收集事件处理QPS、P95/P99延迟、规则命中数、告警数、模型分分布、限流次数等核心指标
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsCollector {

    private final MeterRegistry meterRegistry;

    /**
     * 业务线计数器缓存
     */
    private final Map<String, Counter> eventCounterCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> alertCounterCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> ruleHitCounterCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> blockCounterCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> rateLimitCounterCache = new ConcurrentHashMap<>();

    private Counter totalEventCounter;
    private Counter totalAlertCounter;
    private Counter totalBlockCounter;
    private Counter totalRateLimitCounter;

    private Timer eventProcessingTimer;
    private Timer ruleEvaluationTimer;
    private Timer alertProcessingTimer;

    private DistributionSummary modelScoreDistribution;
    private DistributionSummary riskScoreDistribution;

    private final AtomicLong activeAlerts = new AtomicLong(0);
    private final AtomicLong pendingReviews = new AtomicLong(0);

    @PostConstruct
    public void initMetrics() {
        totalEventCounter = Counter.builder("risk_events_total")
                .description("风控事件处理总数")
                .register(meterRegistry);

        totalAlertCounter = Counter.builder("risk_alerts_total")
                .description("风控告警产生总数")
                .register(meterRegistry);

        totalBlockCounter = Counter.builder("risk_blocks_total")
                .description("风控拦截总数")
                .register(meterRegistry);

        totalRateLimitCounter = Counter.builder("risk_rate_limit_total")
                .description("风控限流触发总数")
                .register(meterRegistry);

        eventProcessingTimer = Timer.builder("risk_event_processing_duration")
                .description("事件处理耗时")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        ruleEvaluationTimer = Timer.builder("risk_rule_evaluation_duration")
                .description("规则评估耗时")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        alertProcessingTimer = Timer.builder("risk_alert_processing_duration")
                .description("告警处理耗时")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        modelScoreDistribution = DistributionSummary.builder("risk_model_score_distribution")
                .description("模型评分分布")
                .publishPercentiles(0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        riskScoreDistribution = DistributionSummary.builder("risk_final_score_distribution")
                .description("综合风险分分布")
                .publishPercentiles(0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        meterRegistry.gauge("risk_active_alerts", activeAlerts);
        meterRegistry.gauge("risk_pending_reviews", pendingReviews);

        log.info("[MetricsCollector] 指标收集器初始化完成");
    }

    /**
     * 记录事件处理
     */
    public void recordEvent(String businessLine, long durationMs) {
        totalEventCounter.increment();
        getOrCreateCounter(eventCounterCache, "risk_events_by_businessline",
                "business_line", businessLine).increment();
        eventProcessingTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录告警产生
     */
    public void recordAlert(String businessLine, String severity, long durationMs) {
        totalAlertCounter.increment();
        activeAlerts.incrementAndGet();
        getOrCreateCounter(alertCounterCache, "risk_alerts_by_businessline",
                "business_line", businessLine).increment();
        alertProcessingTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录规则命中
     */
    public void recordRuleHit(String ruleId, String businessLine, long durationMs) {
        String key = ruleId + ":" + businessLine;
        getOrCreateCounter(ruleHitCounterCache, "risk_rule_hits",
                "rule_id", ruleId, "business_line", businessLine).increment();
        ruleEvaluationTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录拦截操作
     */
    public void recordBlock(String businessLine, String blockType) {
        totalBlockCounter.increment();
        getOrCreateCounter(blockCounterCache, "risk_blocks_by_type",
                "business_line", businessLine, "block_type", blockType).increment();
    }

    /**
     * 记录限流操作
     */
    public void recordRateLimit(String businessLine) {
        totalRateLimitCounter.increment();
        getOrCreateCounter(rateLimitCounterCache, "risk_rate_limit_by_businessline",
                "business_line", businessLine).increment();
    }

    /**
     * 记录模型分数
     */
    public void recordModelScore(double score) {
        modelScoreDistribution.record(clampScore(score));
    }

    /**
     * 记录综合风险分
     */
    public void recordRiskScore(double score) {
        riskScoreDistribution.record(clampScore(score));
    }

    /**
     * 告警关闭时减少活跃告警数
     */
    public void decrementActiveAlerts() {
        activeAlerts.updateAndGet(v -> Math.max(0, v - 1));
    }

    /**
     * 增加待审核工单数
     */
    public void incrementPendingReviews() {
        pendingReviews.incrementAndGet();
    }

    /**
     * 减少待审核工单数
     */
    public void decrementPendingReviews() {
        pendingReviews.updateAndGet(v -> Math.max(0, v - 1));
    }

    /**
     * 获取P95延迟（毫秒）
     */
    public double getEventProcessingP95Ms() {
        return eventProcessingTimer.takeSnapshot().percentileValues().stream()
                .filter(pv -> Math.abs(pv.percentile() - 0.95) < 0.001)
                .findFirst()
                .map(pv -> pv.value(TimeUnit.MILLISECONDS))
                .orElse(0.0);
    }

    /**
     * 获取P99延迟（毫秒）
     */
    public double getEventProcessingP99Ms() {
        return eventProcessingTimer.takeSnapshot().percentileValues().stream()
                .filter(pv -> Math.abs(pv.percentile() - 0.99) < 0.001)
                .findFirst()
                .map(pv -> pv.value(TimeUnit.MILLISECONDS))
                .orElse(0.0);
    }

    /**
     * 获取事件QPS（近似值）
     */
    public double getEventQPS() {
        return meterRegistry.get("risk_events_total").counter().count() /
                Math.max(1, Duration.ofMinutes(1).getSeconds());
    }

    /**
     * 获取当前活跃告警数
     */
    public long getActiveAlertCount() {
        return activeAlerts.get();
    }

    /**
     * 获取待审核工单数
     */
    public long getPendingReviewCount() {
        return pendingReviews.get();
    }

    /**
     * 获取或创建业务线计数器
     */
    private Counter getOrCreateCounter(Map<String, Counter> cache, String metricName,
                                       String... tagKeyValuePairs) {
        String cacheKey = String.join(":", tagKeyValuePairs);
        return cache.computeIfAbsent(cacheKey, k -> {
            Counter.Builder builder = Counter.builder(metricName);
            for (int i = 0; i < tagKeyValuePairs.length; i += 2) {
                builder.tag(tagKeyValuePairs[i], tagKeyValuePairs[i + 1]);
            }
            return builder.register(meterRegistry);
        });
    }

    /**
     * 将分数限制在[0, 1]范围内
     */
    private double clampScore(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }
}
