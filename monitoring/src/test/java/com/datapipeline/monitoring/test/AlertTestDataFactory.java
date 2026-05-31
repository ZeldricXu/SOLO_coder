package com.datapipeline.monitoring.test;

import com.datapipeline.common.model.StatisticsSnapshot;
import com.datapipeline.common.test.TestDataFactory;
import com.datapipeline.monitoring.alert.AlertRule;
import com.datapipeline.monitoring.alert.AlertRule.Operator;
import com.datapipeline.monitoring.alert.AlertRule.Severity;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class AlertTestDataFactory {

    private static final ThreadLocalRandom RANDOM = ThreadLocalRandom.current();

    private AlertTestDataFactory() {}

    public static AlertRule createAlertRule(String metricName, Operator op, Number threshold, Severity severity) {
        return AlertRule.builder()
                .ruleId("rule_" + System.currentTimeMillis() + "_" + RANDOM.nextInt(1000))
                .metricName(metricName)
                .operator(op)
                .threshold(threshold)
                .severity(severity)
                .enabled(true)
                .labels(Map.of("env", "test"))
                .build();
    }

    public static AlertRule createHighErrorRateRule() {
        return createAlertRule("error_rate", Operator.GT, 0.05, Severity.CRITICAL);
    }

    public static AlertRule createHighLatencyRule() {
        return createAlertRule("latency_p99", Operator.GT, 200.0, Severity.WARNING);
    }

    public static AlertRule createLowThroughputRule() {
        return createAlertRule("throughput", Operator.LT, 100.0, Severity.WARNING);
    }

    public static StatisticsSnapshot createErrorRateSnapshot(double errorRate) {
        return TestDataFactory.createStatisticsSnapshot(Map.of(
                "throughput", 1000.0,
                "latency_p99", 100.0,
                "error_rate", errorRate
        ));
    }

    public static StatisticsSnapshot createLatencySnapshot(double latency) {
        return TestDataFactory.createStatisticsSnapshot(Map.of(
                "throughput", 1000.0,
                "latency_p99", latency,
                "error_rate", 0.01
        ));
    }

    public static StatisticsSnapshot createThroughputSnapshot(double throughput) {
        return TestDataFactory.createStatisticsSnapshot(Map.of(
                "throughput", throughput,
                "latency_p99", 100.0,
                "error_rate", 0.01
        ));
    }

}
