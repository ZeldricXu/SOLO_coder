package com.loganalytics.detector.anomaly;

import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.detector.config.DetectorConfig;
import com.loganalytics.test.builder.LogEventBuilder;
import com.loganalytics.test.builder.LogPatternBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContentAnomalyDetector - 内容异常检测")
class ContentAnomalyDetectorTest {

    private ContentAnomalyDetector detector;
    private DetectorConfig config;

    @BeforeEach
    void setUp() {
        config = new DetectorConfig();
        config.setSigmaThreshold(3.0);
        config.setFrequencyWindowMinutes(5);
        config.setBaselineHistoryDays(14);
        config.setMinBaselinePoints(10);
        config.setAnomalyCooldownMinutes(0);
        config.setSimilarityThreshold(0.7);
        config.setMaxTreeDepth(4);
        config.setMaxChildren(100);

        detector = new ContentAnomalyDetector(config);
    }

    @Test
    @DisplayName("内容异常检测在从未见过的模式出现时触发标记")
    void shouldTriggerContentAnomalyForNeverSeenPattern() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asNew()
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_123", "192.168.1.100")
                .withPatternId(pattern.getId())
                .build();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern), List.of(event));

        assertThat(anomalies).isNotEmpty();
        AnomalyEvent anomaly = anomalies.get(0);
        assertThat(anomaly.getType()).isEqualTo(AnomalyEvent.AnomalyType.CONTENT);
        assertThat(anomaly.getPatternId()).isEqualTo(pattern.getId());
        assertThat(anomaly.getPatternTemplate()).isEqualTo(pattern.getTemplate());
        assertThat(detector.hasSeenPattern(pattern.getId())).isTrue();
    }

    @Test
    @DisplayName("已见过的模式不触发内容异常")
    void shouldNotTriggerAnomalyForAlreadySeenPattern() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asNew()
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_1", "192.168.1.1")
                .withPatternId(pattern.getId())
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_2", "10.0.0.1")
                .withPatternId(pattern.getId())
                .build();

        List<AnomalyEvent> firstDetection = detector.detect(List.of(pattern), List.of(event1));
        assertThat(firstDetection).hasSize(1);

        List<AnomalyEvent> secondDetection = detector.detect(List.of(pattern), List.of(event2));
        assertThat(secondDetection).isEmpty();
    }

    @Test
    @DisplayName("ERROR级别新模式触发HIGH严重级别")
    void shouldSetHighSeverityForErrorLevelNewPattern() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withConnectionTimeoutTemplate()
                .asNew()
                .withPaymentService()
                .withLevelError()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withLevelError()
                .withConnectionTimeoutMessage("db-server", 5432, 30)
                .withPatternId(pattern.getId())
                .build();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern), List.of(event));

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(0).getSeverity()).isEqualTo(AnomalyEvent.Severity.HIGH);
    }

    @Test
    @DisplayName("包含关键错误关键字的新模式触发CRITICAL严重级别")
    void shouldSetCriticalSeverityForPatternWithCriticalKeywords() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withTemplate("Out of memory error occurred while processing request")
                .asNew()
                .withPaymentService()
                .withLevelError()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withLevelError()
                .withMessage("Out of memory error occurred while processing request")
                .withPatternId(pattern.getId())
                .build();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern), List.of(event));

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(0).getSeverity()).isEqualTo(AnomalyEvent.Severity.CRITICAL);
    }

    @Test
    @DisplayName("WARN级别新模式触发MEDIUM严重级别")
    void shouldSetMediumSeverityForWarnLevelNewPattern() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withTemplate("Connection pool usage is high: <*>%")
                .asNew()
                .withGatewayService()
                .withLevelWarn()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withLevelWarn()
                .withMessage("Connection pool usage is high: 85%")
                .withPatternId(pattern.getId())
                .build();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern), List.of(event));

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(0).getSeverity()).isEqualTo(AnomalyEvent.Severity.MEDIUM);
    }

    @Test
    @DisplayName("INFO级别新模式触发LOW严重级别")
    void shouldSetLowSeverityForInfoLevelNewPattern() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asNew()
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withUserLoginMessage("user_123", "192.168.1.100")
                .withPatternId(pattern.getId())
                .build();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern), List.of(event));

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(0).getSeverity()).isEqualTo(AnomalyEvent.Severity.LOW);
    }

    @Test
    @DisplayName("包含可疑关键字的新模式触发MEDIUM严重级别")
    void shouldSetMediumSeverityForSuspiciousPattern() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withTemplate("Failed to authenticate user <*> from <*>")
                .asNew()
                .withGatewayService()
                .withLevelInfo()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withMessage("Failed to authenticate user admin from 192.168.1.100")
                .withPatternId(pattern.getId())
                .build();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern), List.of(event));

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(0).getSeverity()).isEqualTo(AnomalyEvent.Severity.MEDIUM);
    }

    @Test
    @DisplayName("同时检测多个新模式的异常")
    void shouldDetectAnomaliesForMultipleNewPatterns() {
        LogPattern pattern1 = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asNew()
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogPattern pattern2 = LogPatternBuilder.aLogPattern()
                .withConnectionTimeoutTemplate()
                .asNew()
                .withGatewayService()
                .withLevelError()
                .build();

        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_1", "192.168.1.1")
                .withPatternId(pattern1.getId())
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withLevelError()
                .withConnectionTimeoutMessage("db-server", 5432, 30)
                .withPatternId(pattern2.getId())
                .build();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern1, pattern2), List.of(event1, event2));

        assertThat(anomalies).hasSize(2);
        assertThat(anomalies).extracting("patternId")
                .containsExactlyInAnyOrder(pattern1.getId(), pattern2.getId());
    }

    @Test
    @DisplayName("找不到匹配的LogEvent时不创建异常")
    void shouldNotCreateAnomalyWhenNoMatchingLogEvent() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asNew()
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withMessage("Different message")
                .withPatternId("different-pattern-id")
                .build();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern), List.of(event));

        assertThat(anomalies).isEmpty();
    }

    @Test
    @DisplayName("异常事件包含完整的诊断信息")
    void shouldIncludeDiagnosticInformationInAnomaly() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withConnectionTimeoutTemplate()
                .asNew()
                .withPaymentService()
                .withLevelError()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withLevelError()
                .withConnectionTimeoutMessage("db-server-01", 5432, 30)
                .withPatternId(pattern.getId())
                .withHostname("host-01")
                .withTraceId("trace-12345")
                .build();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern), List.of(event));

        assertThat(anomalies).isNotEmpty();
        AnomalyEvent anomaly = anomalies.get(0);

        assertThat(anomaly.getServiceName()).isEqualTo(event.getServiceName());
        assertThat(anomaly.getLevel()).isEqualTo(event.getLevel().name());
        assertThat(anomaly.getTraceId()).isEqualTo(event.getTraceId());
        assertThat(anomaly.getDetails()).containsKey("firstSeen");
        assertThat(anomaly.getDetails()).containsKey("sampleMessage");
        assertThat(anomaly.getDetails()).containsKey("sampleHost");
        assertThat(anomaly.getDetails()).containsKey("isErrorPattern");
        assertThat(anomaly.getDetails()).containsKey("containsCriticalKeyword");
        assertThat(anomaly.getDetails()).containsKey("patternStaticTokens");
        assertThat(anomaly.getDetails()).containsKey("patternVariableSlots");
        assertThat(anomaly.getDetails()).containsKey("description");

        String description = (String) anomaly.getDetails().get("description");
        assertThat(description).contains("New log pattern detected");
    }

    @Test
    @DisplayName("获取指定时间范围内的新模式数量")
    void shouldGetNewPatternCountForTimeRange() throws InterruptedException {
        LogPattern pattern1 = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asNew()
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_1", "192.168.1.1")
                .withPatternId(pattern1.getId())
                .build();

        detector.detect(List.of(pattern1), List.of(event1));

        Thread.sleep(100);

        LogPattern pattern2 = LogPatternBuilder.aLogPattern()
                .withConnectionTimeoutTemplate()
                .asNew()
                .withGatewayService()
                .withLevelError()
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withLevelError()
                .withConnectionTimeoutMessage("db-server", 5432, 30)
                .withPatternId(pattern2.getId())
                .build();

        detector.detect(List.of(pattern2), List.of(event2));

        int lastHourCount = detector.getNewPatternCountLast(Duration.ofHours(1));
        int lastMinuteCount = detector.getNewPatternCountLast(Duration.ofMinutes(1));

        assertThat(lastHourCount).isEqualTo(2);
        assertThat(lastMinuteCount).isEqualTo(2);
    }

    @Test
    @DisplayName("冷却期内不重复触发异常")
    void shouldNotTriggerDuplicateAnomalyWithinCooldown() {
        config.setAnomalyCooldownMinutes(10);
        detector = new ContentAnomalyDetector(config);

        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asNew()
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_1", "192.168.1.1")
                .withPatternId(pattern.getId())
                .build();

        assertThat(detector.hasSeenPattern(pattern.getId())).isFalse();
    }

    @Test
    @DisplayName("获取诊断信息")
    void shouldReturnDiagnostics() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asNew()
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_1", "192.168.1.1")
                .withPatternId(pattern.getId())
                .build();

        detector.detect(List.of(pattern), List.of(event));

        Map<String, Object> diagnostics = detector.getDiagnostics();

        assertThat(diagnostics).containsEntry("totalSeenPatterns", 1);
        assertThat(diagnostics).containsKey("newLastHour");
        assertThat(diagnostics).containsKey("newLastDay");
        assertThat(diagnostics).containsKey("criticalKeywords");
    }
}
