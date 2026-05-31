package com.datapipeline.monitoring.alert;

import com.datapipeline.common.model.StatisticsSnapshot;
import com.datapipeline.common.test.TestDataFactory;
import com.datapipeline.common.test.TestUtils;
import com.datapipeline.monitoring.alert.AlertRule.*;
import com.datapipeline.monitoring.test.AlertTestDataFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AlertRuleEngineTest {

    private AlertRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AlertRuleEngine();
    }

    @Nested
    @DisplayName("基本告警规则测试")
    class BasicRuleTests {

        @Test
        @DisplayName("应成功添加告警规则")
        void testAddRule() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("rule_001")
                    .metricName("error_rate")
                    .operator(Operator.GT)
                    .threshold(0.05)
                    .severity(Severity.CRITICAL)
                    .build();

            engine.addRule(rule);

            assertThat(engine.getRules()).hasSize(1);
            assertThat(engine.getRules().get(0).getRuleId()).isEqualTo("rule_001");
        }

        @Test
        @DisplayName("应成功移除告警规则")
        void testRemoveRule() {
            AlertRule rule1 = AlertRule.builder().ruleId("rule_001").metricName("metric1").build();
            AlertRule rule2 = AlertRule.builder().ruleId("rule_002").metricName("metric2").build();

            engine.addRule(rule1);
            engine.addRule(rule2);

            assertThat(engine.getRules()).hasSize(2);

            engine.removeRule("rule_001");

            assertThat(engine.getRules()).hasSize(1);
            assertThat(engine.getRules().get(0).getRuleId()).isEqualTo("rule_002");
        }

        @Test
        @DisplayName("移除不存在的规则应安全处理")
        void testRemoveNonExistentRule() {
            Assertions.assertDoesNotThrow(() -> engine.removeRule("nonexistent"));
            assertThat(engine.getRules()).isEmpty();
        }

    }

    @Nested
    @DisplayName("告警触发测试")
    class AlertFiringTests {

        @Test
        @DisplayName("大于阈值时应触发告警")
        void testGreaterThanThreshold() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("error_rule")
                    .metricName("error_rate")
                    .operator(Operator.GT)
                    .threshold(0.05)
                    .severity(Severity.CRITICAL)
                    .enabled(true)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshotWithErrorRate(0.10);
            engine.evaluate(snapshot);

            assertThat(engine.getActiveAlerts()).hasSize(1);
            AlertEvent event = engine.getActiveAlerts().get("error_rule");
            assertThat(event).isNotNull();
            assertThat(event.getStatus()).isEqualTo(AlertEvent.Status.FIRING);
            assertThat(event.getSeverity()).isEqualTo(Severity.CRITICAL);
        }

        @Test
        @DisplayName("小于阈值时应触发告警")
        void testLessThanThreshold() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("throughput_rule")
                    .metricName("throughput")
                    .operator(Operator.LT)
                    .threshold(100.0)
                    .severity(Severity.WARNING)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshot(Map.of(
                    "throughput", 50.0
            ));
            engine.evaluate(snapshot);

            assertThat(engine.getActiveAlerts()).hasSize(1);
        }

        @Test
        @DisplayName("等于阈值时应触发告警")
        void testEqualThreshold() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("eq_rule")
                    .metricName("error_count")
                    .operator(Operator.EQ)
                    .threshold(5)
                    .severity(Severity.INFO)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshot(Map.of(
                    "error_count", 5
            ));
            engine.evaluate(snapshot);

            assertThat(engine.getActiveAlerts()).hasSize(1);
        }

        @Test
        @DisplayName("未超过阈值时不应触发告警")
        void testNoAlertWhenBelowThreshold() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("safe_rule")
                    .metricName("error_rate")
                    .operator(Operator.GT)
                    .threshold(0.10)
                    .severity(Severity.CRITICAL)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshotWithErrorRate(0.05);
            engine.evaluate(snapshot);

            assertThat(engine.getActiveAlerts()).isEmpty();
        }

    }

    @Nested
    @DisplayName("告警恢复测试")
    class AlertResolutionTests {

        @Test
        @DisplayName("指标恢复正常时告警应解除")
        void testAlertResolution() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("test_rule")
                    .metricName("latency_p99")
                    .operator(Operator.GT)
                    .threshold(200.0)
                    .severity(Severity.WARNING)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot highLatency = TestDataFactory.createStatisticsSnapshot(Map.of(
                    "latency_p99", 300.0
            ));
            engine.evaluate(highLatency);

            assertThat(engine.getActiveAlerts()).hasSize(1);

            StatisticsSnapshot normalLatency = TestDataFactory.createStatisticsSnapshot(Map.of(
                    "latency_p99", 100.0
            ));
            engine.evaluate(normalLatency);

            assertThat(engine.getActiveAlerts()).isEmpty();
        }

    }

    @Nested
    @DisplayName("操作符测试")
    class OperatorTests {

        @Test
        @DisplayName("所有操作符应正确工作")
        void testAllOperators() {
            Operator[] operators = {Operator.GT, Operator.GTE, Operator.LT, Operator.LTE, Operator.EQ, Operator.NEQ};
            Number threshold = 50;

            for (Operator op : operators) {
                AlertRule rule = AlertRule.builder()
                        .ruleId("rule_" + op.name())
                        .metricName("metric_" + op.name())
                        .operator(op)
                        .threshold(threshold)
                        .severity(Severity.INFO)
                        .build();
                engine.addRule(rule);
            }

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshot(Map.of(
                    "metric_GT", 60,
                    "metric_GTE", 50,
                    "metric_LT", 40,
                    "metric_LTE", 50,
                    "metric_EQ", 50,
                    "metric_NEQ", 51
            ));

            engine.evaluate(snapshot);

            assertThat(engine.getActiveAlerts()).hasSize(6);
        }

    }

    @Nested
    @DisplayName("禁用规则测试")
    class DisabledRuleTests {

        @Test
        @DisplayName("禁用的规则不应触发告警")
        void testDisabledRule() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("disabled_rule")
                    .metricName("error_rate")
                    .operator(Operator.GT)
                    .threshold(0.01)
                    .severity(Severity.CRITICAL)
                    .enabled(false)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshotWithErrorRate(0.10);
            engine.evaluate(snapshot);

            assertThat(engine.getActiveAlerts()).isEmpty();
        }

    }

    @Nested
    @DisplayName("监听器测试")
    class ListenerTests {

        @Test
        @DisplayName("告警触发时应通知监听器")
        void testAlertListener() {
            List<AlertEvent> receivedEvents = new ArrayList<>();
            engine.registerListener(receivedEvents::add);

            AlertRule rule = AlertRule.builder()
                    .ruleId("listener_test")
                    .metricName("error_rate")
                    .operator(Operator.GT)
                    .threshold(0.05)
                    .severity(Severity.WARNING)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshotWithErrorRate(0.10);
            engine.evaluate(snapshot);

            assertThat(receivedEvents).hasSize(1);
            assertThat(receivedEvents.get(0).getStatus()).isEqualTo(AlertEvent.Status.FIRING);
        }

        @Test
        @DisplayName("告警恢复时应通知监听器")
        void testAlertResolutionListener() {
            List<AlertEvent> receivedEvents = new ArrayList<>();
            engine.registerListener(receivedEvents::add);

            AlertRule rule = AlertRule.builder()
                    .ruleId("resolution_test")
                    .metricName("latency")
                    .operator(Operator.GT)
                    .threshold(100.0)
                    .severity(Severity.INFO)
                    .build();

            engine.addRule(rule);

            engine.evaluate(TestDataFactory.createStatisticsSnapshot(Map.of("latency", 200.0)));
            engine.evaluate(TestDataFactory.createStatisticsSnapshot(Map.of("latency", 50.0)));

            assertThat(receivedEvents).hasSize(2);
            assertThat(receivedEvents.get(1).getStatus()).isEqualTo(AlertEvent.Status.RESOLVED);
        }

    }

    @Nested
    @DisplayName("并发安全测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发添加规则应线程安全")
        void testConcurrentAddRules() throws Exception {
            int ruleCount = 100;

            TestUtils.executeConcurrently(10, 10, iteration -> {
                AlertRule rule = AlertRule.builder()
                        .ruleId("rule_" + iteration)
                        .metricName("metric_" + iteration)
                        .operator(Operator.GT)
                        .threshold(iteration * 1.0)
                        .severity(Severity.INFO)
                        .build();
                engine.addRule(rule);
            });

            assertThat(engine.getRules()).hasSize(ruleCount);
        }

        @Test
        @DisplayName("并发评估应线程安全")
        void testConcurrentEvaluation() throws Exception {
            AlertRule errorRateRule = AlertTestDataFactory.createHighErrorRateRule();
            AlertRule latencyRule = AlertTestDataFactory.createHighLatencyRule();

            engine.addRule(errorRateRule);
            engine.addRule(latencyRule);

            int threadCount = 20;
            int iterationsPerThread = 50;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterationsPerThread; j++) {
                            double errorRate = (index + j) % 2 == 0 ? 0.01 : 0.10;
                            double latency = (index + j) % 2 == 0 ? 100.0 : 300.0;

                            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshot(Map.of(
                                    "error_rate", errorRate,
                                    "latency_p99", latency
                            ));

                            engine.evaluate(snapshot);
                        }
                        successCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);

            assertThat(successCount.get()).isEqualTo(threadCount);
        }

        @Test
        @DisplayName("并发获取活动告警应线程安全")
        void testConcurrentGetActiveAlerts() throws Exception {
            AlertRule rule = AlertRule.builder()
                    .ruleId("concurrent_rule")
                    .metricName("metric")
                    .operator(Operator.GT)
                    .threshold(50.0)
                    .severity(Severity.WARNING)
                    .build();
            engine.addRule(rule);

            engine.evaluate(TestDataFactory.createStatisticsSnapshot(Map.of("metric", 100.0)));

            TestUtils.executeConcurrently(20, 100, iteration -> {
                Map<String, AlertEvent> alerts = engine.getActiveAlerts();
                assertThat(alerts).isNotNull();
            });
        }

    }

    @Nested
    @DisplayName("多规则测试")
    class MultipleRuleTests {

        @Test
        @DisplayName("多个规则应独立评估")
        void testMultipleIndependentRules() {
            AlertRule errorRule = AlertRule.builder()
                    .ruleId("error_rule")
                    .metricName("error_rate")
                    .operator(Operator.GT)
                    .threshold(0.05)
                    .severity(Severity.CRITICAL)
                    .build();

            AlertRule latencyRule = AlertRule.builder()
                    .ruleId("latency_rule")
                    .metricName("latency_p99")
                    .operator(Operator.GT)
                    .threshold(200.0)
                    .severity(Severity.WARNING)
                    .build();

            engine.addRule(errorRule);
            engine.addRule(latencyRule);

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshot(Map.of(
                    "error_rate", 0.02,
                    "latency_p99", 300.0
            ));

            engine.evaluate(snapshot);

            assertThat(engine.getActiveAlerts()).hasSize(1);
            assertThat(engine.getActiveAlerts()).containsKey("latency_rule");
            assertThat(engine.getActiveAlerts()).doesNotContainKey("error_rule");
        }

        @Test
        @DisplayName("多个规则可同时触发")
        void testMultipleRulesFiring() {
            AlertRule errorRule = AlertRule.builder()
                    .ruleId("error_rule")
                    .metricName("error_rate")
                    .operator(Operator.GT)
                    .threshold(0.01)
                    .severity(Severity.CRITICAL)
                    .build();

            AlertRule latencyRule = AlertRule.builder()
                    .ruleId("latency_rule")
                    .metricName("latency_p99")
                    .operator(Operator.GT)
                    .threshold(100.0)
                    .severity(Severity.WARNING)
                    .build();

            engine.addRule(errorRule);
            engine.addRule(latencyRule);

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshot(Map.of(
                    "error_rate", 0.10,
                    "latency_p99", 200.0
            ));

            engine.evaluate(snapshot);

            assertThat(engine.getActiveAlerts()).hasSize(2);
        }

    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("零值应正确比较")
        void testZeroValueComparison() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("zero_rule")
                    .metricName("errors")
                    .operator(Operator.GT)
                    .threshold(0)
                    .severity(Severity.INFO)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot noErrors = TestDataFactory.createStatisticsSnapshot(Map.of("errors", 0));
            engine.evaluate(noErrors);
            assertThat(engine.getActiveAlerts()).isEmpty();

            StatisticsSnapshot hasErrors = TestDataFactory.createStatisticsSnapshot(Map.of("errors", 1));
            engine.evaluate(hasErrors);
            assertThat(engine.getActiveAlerts()).hasSize(1);
        }

        @Test
        @DisplayName("缺失的指标应被忽略")
        void testMissingMetricIgnored() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("missing_rule")
                    .metricName("non_existent_metric")
                    .operator(Operator.GT)
                    .threshold(10.0)
                    .severity(Severity.WARNING)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot snapshot = TestDataFactory.createStatisticsSnapshot(Map.of(
                    "other_metric", 100.0
            ));

            Assertions.assertDoesNotThrow(() -> engine.evaluate(snapshot));
            assertThat(engine.getActiveAlerts()).isEmpty();
        }

        @Test
        @DisplayName("空快照应安全处理")
        void testEmptySnapshot() {
            AlertRule rule = AlertRule.builder()
                    .ruleId("empty_rule")
                    .metricName("metric")
                    .operator(Operator.GT)
                    .threshold(1.0)
                    .severity(Severity.INFO)
                    .build();

            engine.addRule(rule);

            StatisticsSnapshot emptySnapshot = TestDataFactory.createStatisticsSnapshot(Collections.emptyMap());

            Assertions.assertDoesNotThrow(() -> engine.evaluate(emptySnapshot));
        }

    }

}
