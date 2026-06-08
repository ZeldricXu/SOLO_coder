package com.datateam.loganalyzer.alert;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.AlertRule;
import com.datateam.loganalyzer.model.AlertSeverity;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("告警规则引擎单元测试")
class AlertRuleEngineTest {

    private AlertRuleEngine engine;
    private List<TimeSeriesPoint> testTimeSeries;

    @BeforeEach
    void setUp() {
        engine = new AlertRuleEngine();
        testTimeSeries = generateTestTimeSeries();
    }

    @Test
    @DisplayName("正常路径：阈值规则正确触发")
    void testThresholdRuleTriggers() {
        AlertRule rule = createThresholdRule("error-rule", "ERROR count > 3",
                "errors", AlertRule.Comparison.GT, 3.0, 1);

        engine.addRule(rule);
        List<AlertEvent> alerts = engine.evaluate(testTimeSeries);

        assertThat(alerts).isNotEmpty();
        assertThat(alerts.get(0).getRuleName()).isEqualTo("ERROR count > 3");
        assertThat(alerts.get(0).getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(alerts.get(0).getDescription()).contains("triggered");
    }

    @Test
    @DisplayName("正常路径：冷却期内重复触发的事件被正确抑制")
    void testCooldownSuppressesDuplicates() throws InterruptedException {
        AlertRule rule = createThresholdRule("error-rule", "ERROR count > 3",
                "errors", AlertRule.Comparison.GT, 3.0, 1);
        rule.setCooldownSeconds(300);

        engine.addRule(rule);

        List<AlertEvent> firstEvaluation = engine.evaluate(testTimeSeries);
        assertThat(firstEvaluation).hasSize(1);

        List<AlertEvent> secondEvaluation = engine.evaluate(testTimeSeries);
        assertThat(secondEvaluation).isEmpty();
    }

    @Test
    @DisplayName("异常路径：引用不存在的指标字段时正确处理")
    void testNonExistentMetricHandling() {
        AlertRule rule = createThresholdRule("bad-rule", "Non existent metric",
                "nonexistent.metric", AlertRule.Comparison.GT, 10.0, 1);

        engine.addRule(rule);
        List<AlertEvent> alerts = engine.evaluate(testTimeSeries);

        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("正常路径：复合规则AND逻辑正确")
    void testCompositeRuleAndLogic() {
        AlertRule errorRule = createThresholdRule("error-rule", "ERROR count > 2",
                "errors", AlertRule.Comparison.GT, 2.0, 1);
        AlertRule totalRule = createThresholdRule("total-rule", "Total count > 8",
                "total", AlertRule.Comparison.GT, 8.0, 1);

        AlertRule compositeRule = new AlertRule();
        compositeRule.setId("composite-and");
        compositeRule.setName("High error rate");
        compositeRule.setType(AlertRule.RuleType.COMPOSITE);
        compositeRule.setOperator(AlertRule.Operator.AND);
        compositeRule.setSeverity(AlertSeverity.CRITICAL);
        compositeRule.addChild(errorRule);
        compositeRule.addChild(totalRule);

        engine.addRule(compositeRule);
        List<AlertEvent> alerts = engine.evaluate(testTimeSeries);

        assertThat(alerts).isNotEmpty();
        assertThat(alerts.get(0).getRuleName()).isEqualTo("High error rate");
    }

    @Test
    @DisplayName("正常路径：复合规则OR逻辑正确")
    void testCompositeRuleOrLogic() {
        AlertRule errorRule = createThresholdRule("error-rule", "ERROR count > 100",
                "errors", AlertRule.Comparison.GT, 100.0, 1);
        AlertRule totalRule = createThresholdRule("total-rule", "Total count > 5",
                "total", AlertRule.Comparison.GT, 5.0, 1);

        AlertRule compositeRule = new AlertRule();
        compositeRule.setId("composite-or");
        compositeRule.setName("Either condition");
        compositeRule.setType(AlertRule.RuleType.COMPOSITE);
        compositeRule.setOperator(AlertRule.Operator.OR);
        compositeRule.setSeverity(AlertSeverity.WARNING);
        compositeRule.addChild(errorRule);
        compositeRule.addChild(totalRule);

        engine.addRule(compositeRule);
        List<AlertEvent> alerts = engine.evaluate(testTimeSeries);

        assertThat(alerts).isNotEmpty();
    }

    @Test
    @DisplayName("正常路径：最小违规次数配置生效")
    void testMinViolationsConfiguration() {
        AlertRule rule = createThresholdRule("error-rule", "ERROR count > 2 for 2 consecutive windows",
                "errors", AlertRule.Comparison.GT, 2.0, 2);

        engine.addRule(rule);
        List<AlertEvent> alerts = engine.evaluate(testTimeSeries);

        long consecutiveErrorWindows = testTimeSeries.stream()
                .filter(p -> p.getErrorCount() > 2)
                .count();

        assertThat(consecutiveErrorWindows).isGreaterThanOrEqualTo(2);
        assertThat(alerts).isNotEmpty();
    }

    @Test
    @DisplayName("异常路径：禁用的规则不触发")
    void testDisabledRulesDoNotTrigger() {
        AlertRule rule = createThresholdRule("error-rule", "ERROR count > 3",
                "errors", AlertRule.Comparison.GT, 3.0, 1);
        rule.setEnabled(false);

        engine.addRule(rule);
        List<AlertEvent> alerts = engine.evaluate(testTimeSeries);

        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("正常路径：按服务维度的告警规则")
    void testServiceDimensionAlert() {
        AlertRule rule = createThresholdRule("service-error-rule", "Payment service errors > 0",
                "service:payment-service", AlertRule.Comparison.GT, 0.0, 1);

        engine.addRule(rule);
        List<AlertEvent> alerts = engine.evaluate(testTimeSeries);

        assertThat(alerts).isNotEmpty();
    }

    @Test
    @DisplayName("边界场景：空时间序列不触发告警")
    void testEmptyTimeSeries() {
        AlertRule rule = createThresholdRule("error-rule", "ERROR count > 0",
                "errors", AlertRule.Comparison.GT, 0.0, 1);
        engine.addRule(rule);

        List<AlertEvent> alerts = engine.evaluate(new ArrayList<>());
        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("正常路径：告警升级策略测试")
    void testAlertEscalation() {
        AlertRule rule = createThresholdRule("error-rule", "High error rate",
                "errors", AlertRule.Comparison.GT, 3.0, 1);
        rule.setEscalationMinutes(1);

        engine.addRule(rule);

        List<AlertEvent> initialAlerts = engine.evaluate(testTimeSeries);
        assertThat(initialAlerts).isNotEmpty();

        AlertEvent initialAlert = initialAlerts.get(0);
        initialAlert.setTriggeredAt(Instant.now().minusSeconds(120));

        engine.clearState();
        engine.addRule(rule);
        engine.getActiveAlerts().put(rule.getId(), initialAlert);

        List<AlertEvent> alertsAfterDelay = engine.evaluate(testTimeSeries);

        boolean hasEscalation = alertsAfterDelay.stream()
                .anyMatch(a -> a.getRuleName().contains("ESCALATED"));

        assertThat(hasEscalation).isFalse();
    }

    @Test
    @DisplayName("正常路径：多个规则并行评估")
    void testMultipleRulesEvaluation() {
        AlertRule errorRule = createThresholdRule("error-rule", "ERROR count > 3",
                "errors", AlertRule.Comparison.GT, 3.0, 1);
        errorRule.setSeverity(AlertSeverity.CRITICAL);
        AlertRule warnRule = createThresholdRule("warn-rule", "WARN count > 2",
                "warnings", AlertRule.Comparison.GT, 2.0, 1);
        warnRule.setSeverity(AlertSeverity.WARNING);

        engine.addRule(errorRule);
        engine.addRule(warnRule);

        List<AlertEvent> alerts = engine.evaluate(testTimeSeries);

        assertThat(alerts).hasSize(2);
        assertThat(alerts).extracting(AlertEvent::getSeverity)
                .contains(AlertSeverity.CRITICAL, AlertSeverity.WARNING);
    }

    private AlertRule createThresholdRule(String id, String name, String metric,
                                          AlertRule.Comparison comparison, double threshold,
                                          int minViolations) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setName(name);
        rule.setType(AlertRule.RuleType.THRESHOLD);
        rule.setMetric(metric);
        rule.setComparison(comparison);
        rule.setThreshold(threshold);
        rule.setMinViolations(minViolations);
        rule.setSeverity(AlertSeverity.CRITICAL);
        rule.setCooldownSeconds(60);
        return rule;
    }

    private List<TimeSeriesPoint> generateTestTimeSeries() {
        List<TimeSeriesPoint> points = new ArrayList<>();
        Instant baseTime = Instant.parse("2024-06-01T10:00:00Z");

        for (int i = 0; i < 20; i++) {
            TimeSeriesPoint point = new TimeSeriesPoint();
            point.setWindowStart(baseTime.plusSeconds(i));
            point.setWindowEnd(baseTime.plusSeconds(i + 1));
            point.setDurationSeconds(1);

            for (int j = 0; j < 10; j++) {
                point.incrementTotal();
                point.incrementLevel(LogLevel.INFO);
            }

            if (i >= 5 && i <= 10) {
                for (int j = 0; j < 5; j++) {
                    point.incrementTotal();
                    point.incrementLevel(LogLevel.ERROR);
                }
                point.incrementService("payment-service");
                point.incrementServiceError("payment-service");
            }

            if (i >= 3 && i <= 7) {
                for (int j = 0; j < 3; j++) {
                    point.incrementTotal();
                    point.incrementLevel(LogLevel.WARN);
                }
            }

            point.calculateRates();
            points.add(point);
        }

        return points;
    }
}
