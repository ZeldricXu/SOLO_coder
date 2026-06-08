package com.datateam.loganalyzer.integration;

import com.datateam.loganalyzer.aggregator.TimeSeriesAggregator;
import com.datateam.loganalyzer.alert.AlertRuleEngine;
import com.datateam.loganalyzer.anomaly.ZScoreDetector;
import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.AlertRule;
import com.datateam.loganalyzer.model.AlertSeverity;
import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import com.datateam.loganalyzer.notification.AlertTemplateEngine;
import com.datateam.loganalyzer.parser.LogParser;
import com.datateam.loganalyzer.parser.LogParserFactory;
import com.datateam.loganalyzer.parser.LogFormat;
import com.datateam.loganalyzer.parser.MultiLineMerger;
import com.datateam.loganalyzer.util.FileUtils;
import com.datateam.loganalyzer.util.TimeUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("完整链路集成测试")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullPipelineIntegrationTest {

    private static final String LOG_FILE = "src/test/resources/sample-logs/integration-tests/injected-anomaly.log";
    private static final Instant ANOMALY_START = Instant.parse("2024-06-01T00:00:11Z");
    private static final Instant ANOMALY_END = Instant.parse("2024-06-01T00:00:13Z");

    private List<String> rawLogLines;
    private List<LogEvent> parsedEvents;
    private TimeSeriesAggregator aggregator;
    private List<TimeSeriesPoint> timeSeries;
    private List<AnomalyResult> detectedAnomalies;
    private List<AlertEvent> triggeredAlerts;

    @BeforeAll
    void setUp() throws Exception {
        rawLogLines = FileUtils.readAllLines(LOG_FILE);
    }

    @Test
    @DisplayName("步骤1：日志解析 - 正确解析所有日志行")
    void step1_LogParsing() {
        LogFormat format = LogParserFactory.detectFormat(rawLogLines);
        assertThat(format).isEqualTo(LogFormat.LOG4J);

        LogParser parser = LogParserFactory.createParser(format);
        MultiLineMerger merger = new MultiLineMerger(parser);
        parsedEvents = merger.processLines(rawLogLines);

        assertThat(parsedEvents).hasSize(rawLogLines.size());

        long infoCount = parsedEvents.stream()
                .filter(e -> e.getLevel() == LogLevel.INFO)
                .count();
        long errorCount = parsedEvents.stream()
                .filter(e -> e.getLevel() == LogLevel.ERROR)
                .count();

        assertThat(infoCount).isGreaterThan(0);
        assertThat(errorCount).isEqualTo(20);

        for (LogEvent event : parsedEvents) {
            assertThat(event.getTimestamp()).isNotNull();
            assertThat(event.getLevel()).isNotNull();
            assertThat(event.getRawLine()).isNotNull();
        }
    }

    @Test
    @DisplayName("步骤2：时间序列聚合 - 按秒粒度正确聚合")
    void step2_TimeSeriesAggregation() {
        step1_LogParsing();

        aggregator = new TimeSeriesAggregator(TimeUtils.Granularity.SECOND);
        for (LogEvent event : parsedEvents) {
            aggregator.add(event);
        }

        timeSeries = aggregator.getTimeSeries();

        assertThat(timeSeries).isNotEmpty();
        assertThat(aggregator.getTotalCount()).isEqualTo(parsedEvents.size());
        assertThat(aggregator.getErrorCount()).isEqualTo(20);

        long sum = timeSeries.stream().mapToLong(TimeSeriesPoint::getTotalCount).sum();
        assertThat(sum).isEqualTo(parsedEvents.size());

        for (TimeSeriesPoint point : timeSeries) {
            assertThat(point.getWindowStart()).isNotNull();
            assertThat(point.getWindowEnd()).isNotNull();
            assertThat(point.getTotalCount()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("步骤3：异常检测 - 正确检测注入的异常")
    void step3_AnomalyDetection() {
        step2_TimeSeriesAggregation();

        List<TimeSeriesPoint> baselinePoints = timeSeries.stream()
                .filter(p -> p.getWindowStart().isBefore(ANOMALY_START))
                .toList();

        assertThat(baselinePoints).hasSize(10);

        ZScoreDetector detector = new ZScoreDetector(2.0, 5);
        detector.trainFromTimeSeries(baselinePoints, "errors");

        detectedAnomalies = detector.detectFromTimeSeries(timeSeries, "errors");

        List<AnomalyResult> trueAnomalies = detectedAnomalies.stream()
                .filter(AnomalyResult::isAnomaly)
                .toList();

        assertThat(trueAnomalies).isNotEmpty();

        List<Instant> anomalyTimestamps = trueAnomalies.stream()
                .map(AnomalyResult::getTimestamp)
                .toList();

        boolean foundAnomalyWindow = anomalyTimestamps.stream()
                .anyMatch(ts -> !ts.isBefore(ANOMALY_START) && !ts.isAfter(ANOMALY_END.plusSeconds(1)));

        assertThat(foundAnomalyWindow).isTrue();

        long correctAnomalies = trueAnomalies.stream()
                .filter(a -> !a.getTimestamp().isBefore(ANOMALY_START)
                        && !a.getTimestamp().isAfter(ANOMALY_END.plusSeconds(1)))
                .count();

        long falsePositives = trueAnomalies.stream()
                .filter(a -> a.getTimestamp().isBefore(ANOMALY_START)
                        || a.getTimestamp().isAfter(ANOMALY_END.plusSeconds(1)))
                .count();

        System.out.printf("正确检测到的异常: %d%n", correctAnomalies);
        System.out.printf("误报: %d%n", falsePositives);
        System.out.printf("异常窗口: %s -> %s%n", ANOMALY_START, ANOMALY_END);
        System.out.printf("检测到的异常时间点: %s%n", anomalyTimestamps);

        double falsePositiveRate = (double) falsePositives / timeSeries.size();
        assertThat(falsePositiveRate).isLessThan(0.10);
    }

    @Test
    @DisplayName("步骤4：告警规则匹配 - 正确匹配高错误率规则")
    void step4_AlertRuleMatching() {
        step3_AnomalyDetection();

        AlertRuleEngine ruleEngine = new AlertRuleEngine();

        AlertRule highErrorRateRule = new AlertRule();
        highErrorRateRule.setId("high-error-rate");
        highErrorRateRule.setName("高错误率告警");
        highErrorRateRule.setType(AlertRule.RuleType.THRESHOLD);
        highErrorRateRule.setMetric("errors");
        highErrorRateRule.setComparison(AlertRule.Comparison.GT);
        highErrorRateRule.setThreshold(2.0);
        highErrorRateRule.setMinViolations(1);
        highErrorRateRule.setSeverity(AlertSeverity.CRITICAL);
        highErrorRateRule.setCooldownSeconds(60);

        AlertRule highErrorRatioRule = new AlertRule();
        highErrorRatioRule.setId("high-error-ratio");
        highErrorRatioRule.setName("错误率超过20%");
        highErrorRatioRule.setType(AlertRule.RuleType.THRESHOLD);
        highErrorRatioRule.setMetric("error_ratio");
        highErrorRatioRule.setComparison(AlertRule.Comparison.GT);
        highErrorRatioRule.setThreshold(0.20);
        highErrorRatioRule.setMinViolations(1);
        highErrorRatioRule.setSeverity(AlertSeverity.ERROR);
        highErrorRatioRule.setCooldownSeconds(60);

        ruleEngine.addRule(highErrorRateRule);
        ruleEngine.addRule(highErrorRatioRule);

        triggeredAlerts = ruleEngine.evaluate(timeSeries);

        assertThat(triggeredAlerts).isNotEmpty();

        List<String> triggeredRuleNames = triggeredAlerts.stream()
                .map(AlertEvent::getRuleName)
                .toList();

        assertThat(triggeredRuleNames).contains("高错误率告警");

        boolean hasCriticalAlert = triggeredAlerts.stream()
                .anyMatch(a -> a.getSeverity() == AlertSeverity.CRITICAL);
        assertThat(hasCriticalAlert).isTrue();

        AlertEvent criticalAlert = triggeredAlerts.stream()
                .filter(a -> a.getSeverity() == AlertSeverity.CRITICAL)
                .findFirst()
                .orElse(null);

        assertThat(criticalAlert).isNotNull();
        assertThat(criticalAlert.getDescription()).contains("errors");
        assertThat(criticalAlert.getDescription()).contains("triggered");
    }

    @Test
    @DisplayName("步骤5：通知模板生成 - 正确生成告警通知")
    void step5_NotificationTemplateGeneration() {
        step4_AlertRuleMatching();

        AlertTemplateEngine templateEngine = new AlertTemplateEngine();

        for (AlertEvent alert : triggeredAlerts) {
            String markdown = templateEngine.render(alert);
            String subject = templateEngine.renderSubject(alert);
            String plainText = templateEngine.renderPlainText(alert);
            String wechatMd = templateEngine.getWeChatWorkMarkdown(alert);
            String slackMd = templateEngine.getSlackMarkdown(alert);

            assertThat(markdown).isNotEmpty();
            assertThat(markdown).contains("🚨 告警通知");
            assertThat(markdown).contains(alert.getRuleName());
            assertThat(markdown).contains(alert.getSeverity().toString());

            assertThat(subject).isNotEmpty();
            assertThat(subject).contains(alert.getRuleName());

            assertThat(plainText).isNotEmpty();
            assertThat(plainText).contains("[ALERT]");

            assertThat(wechatMd).isNotEmpty();
            assertThat(slackMd).isNotEmpty();
        }
    }

    @Test
    @DisplayName("完整链路测试 - 解析→聚合→检测→告警→通知")
    void testFullPipeline() throws Exception {
        System.out.println("========== 完整链路集成测试 ==========");
        System.out.printf("日志行数: %d%n", rawLogLines.size());

        step1_LogParsing();
        System.out.printf("解析事件数: %d%n", parsedEvents.size());
        System.out.printf("  - INFO: %d%n", parsedEvents.stream().filter(e -> e.getLevel() == LogLevel.INFO).count());
        System.out.printf("  - ERROR: %d%n", parsedEvents.stream().filter(e -> e.getLevel() == LogLevel.ERROR).count());

        step2_TimeSeriesAggregation();
        System.out.printf("聚合窗口数: %d%n", timeSeries.size());
        System.out.printf("时间范围: %s -> %s%n", aggregator.getStartTime(), aggregator.getEndTime());

        step3_AnomalyDetection();
        long anomalyCount = detectedAnomalies.stream().filter(AnomalyResult::isAnomaly).count();
        System.out.printf("检测到异常: %d 个%n", anomalyCount);

        step4_AlertRuleMatching();
        System.out.printf("触发告警: %d 条%n", triggeredAlerts.size());
        for (AlertEvent alert : triggeredAlerts) {
            System.out.printf("  - [%s] %s%n", alert.getSeverity(), alert.getRuleName());
        }

        step5_NotificationTemplateGeneration();
        System.out.println("通知模板生成: 成功");

        long correctAnomalies = detectedAnomalies.stream()
                .filter(AnomalyResult::isAnomaly)
                .filter(a -> !a.getTimestamp().isBefore(ANOMALY_START)
                        && !a.getTimestamp().isAfter(ANOMALY_END.plusSeconds(1)))
                .count();

        long falsePositives = detectedAnomalies.stream()
                .filter(AnomalyResult::isAnomaly)
                .filter(a -> a.getTimestamp().isBefore(ANOMALY_START)
                        || a.getTimestamp().isAfter(ANOMALY_END.plusSeconds(1)))
                .count();

        long falseNegatives = 3 - correctAnomalies;

        System.out.println("========== 测试结果统计 ==========");
        System.out.printf("注入异常窗口: %s -> %s (共3秒)%n", ANOMALY_START, ANOMALY_END);
        System.out.printf("注入ERROR数量: 20个%n");
        System.out.printf("正确检测: %d 个窗口%n", correctAnomalies);
        System.out.printf("误报: %d 个窗口%n", falsePositives);
        System.out.printf("漏报: %d 个窗口%n", Math.max(0, falseNegatives));
        System.out.printf("误报率: %.2f%%%n", (double) falsePositives / timeSeries.size() * 100);

        assertThat(correctAnomalies).isGreaterThanOrEqualTo(1);
        assertThat(falsePositives).isLessThanOrEqualTo(2);

        boolean hasCorrectAlert = triggeredAlerts.stream()
                .anyMatch(a -> a.getSeverity() == AlertSeverity.CRITICAL);
        assertThat(hasCorrectAlert).isTrue();

        System.out.println("========== 集成测试通过 ==========");
    }
}
