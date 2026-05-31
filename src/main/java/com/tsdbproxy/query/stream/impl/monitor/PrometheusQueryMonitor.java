package com.tsdbproxy.query.stream.impl.monitor;

import com.tsdbproxy.query.stream.model.ParseResult;
import com.tsdbproxy.query.stream.model.QueryStatement;
import com.tsdbproxy.query.stream.spi.QueryMonitor;
import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class PrometheusQueryMonitor implements QueryMonitor {

    private final MeterRegistry meterRegistry;

    private final Counter parseTotalCounter;
    private final Counter parseSuccessCounter;
    private final Counter parseFailCounter;

    private final Timer totalLatencyTimer;
    private final Timer parseLatencyTimer;
    private final Timer optimizeLatencyTimer;
    private final Timer translateLatencyTimer;

    private final DistributionSummary executionTimeSummary;

    private final AtomicLong activeQueries;
    private final AtomicLong lastParseTime;

    private volatile String lastStatus = "idle";

    public PrometheusQueryMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.parseTotalCounter = Counter.builder("query.parse.total")
                .description("总解析次数")
                .register(meterRegistry);
        this.parseSuccessCounter = Counter.builder("query.parse.success")
                .description("成功解析次数")
                .register(meterRegistry);
        this.parseFailCounter = Counter.builder("query.parse.fail")
                .description("失败解析次数")
                .register(meterRegistry);

        this.totalLatencyTimer = Timer.builder("query.parse.latency.total")
                .description("总解析延迟")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.parseLatencyTimer = Timer.builder("query.parse.latency.parse")
                .description("语法解析延迟")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.optimizeLatencyTimer = Timer.builder("query.parse.latency.optimize")
                .description("逻辑优化延迟")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.translateLatencyTimer = Timer.builder("query.parse.latency.translate")
                .description("物理翻译延迟")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.executionTimeSummary = DistributionSummary.builder("query.parse.execution.time")
                .description("执行时间分布")
                .baseUnit("ms")
                .register(meterRegistry);

        this.activeQueries = meterRegistry.gauge("query.parse.active", new AtomicLong(0));
        this.lastParseTime = meterRegistry.gauge("query.parse.last.time", new AtomicLong(0));

        Gauge.builder("query.parse.success.rate", this, PrometheusQueryMonitor::getSuccessRate)
                .description("解析成功率")
                .register(meterRegistry);
    }

    @Override
    public void recordParseSuccess(QueryStatement statement, ParseResult result,
                                   Duration totalTime, Duration parseTime,
                                   Duration optimizeTime, Duration translateTime) {
        parseTotalCounter.increment();
        parseSuccessCounter.increment();
        lastStatus = "success";

        totalLatencyTimer.record(totalTime);
        parseLatencyTimer.record(parseTime);
        optimizeLatencyTimer.record(optimizeTime);
        translateLatencyTimer.record(translateTime);

        executionTimeSummary.record(totalTime.toMillis());
        lastParseTime.set(System.currentTimeMillis());

        log.debug("查询解析成功: sql={}, total={}ms, parse={}ms, optimize={}ms, translate={}ms",
                truncateSql(statement.getSql()),
                totalTime.toMillis(), parseTime.toMillis(), optimizeTime.toMillis(), translateTime.toMillis());
    }

    @Override
    public void recordParseFailure(QueryStatement statement, Exception e, Duration totalTime) {
        parseTotalCounter.increment();
        parseFailCounter.increment();
        lastStatus = "failed";

        totalLatencyTimer.record(totalTime);
        lastParseTime.set(System.currentTimeMillis());

        log.warn("查询解析失败: sql={}, error={}, total={}ms",
                truncateSql(statement.getSql()), e.getMessage(), totalTime.toMillis());
    }

    @Override
    public void recordStage(String stage, Duration duration) {
        log.trace("解析阶段: stage={}, duration={}ms", stage, duration.toMillis());
    }

    @Override
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalCount", parseTotalCounter.count());
        metrics.put("successCount", parseSuccessCounter.count());
        metrics.put("failCount", parseFailCounter.count());
        metrics.put("successRate", getSuccessRate(this));
        metrics.put("status", lastStatus);
        metrics.put("avgLatencyMs", getAverageLatency());
        return metrics;
    }

    @Override
    public String getStatus() {
        return lastStatus;
    }

    public void incrementActive() {
        activeQueries.incrementAndGet();
    }

    public void decrementActive() {
        activeQueries.decrementAndGet();
    }

    private static double getSuccessRate(PrometheusQueryMonitor monitor) {
        double total = monitor.parseTotalCounter.count();
        if (total == 0) return 1.0;
        return monitor.parseSuccessCounter.count() / total;
    }

    private double getAverageLatency() {
        Timer.Sample sample = Timer.start(meterRegistry);
        return totalLatencyTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private String truncateSql(String sql) {
        if (sql == null) return "null";
        return sql.length() > 100 ? sql.substring(0, 100) + "..." : sql;
    }
}
