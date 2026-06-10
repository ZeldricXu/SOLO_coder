package com.loganalytics.detector.anomaly;

import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.detector.baseline.BaselineManager;
import com.loganalytics.detector.config.DetectorConfig;
import com.loganalytics.test.builder.LogPatternBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FrequencyAnomalyDetector - 频率异常检测")
class FrequencyAnomalyDetectorTest {

    private FrequencyAnomalyDetector detector;
    private BaselineManager baselineManager;
    private DetectorConfig config;

    @BeforeEach
    void setUp() {
        config = new DetectorConfig();
        config.setSigmaThreshold(3.0);
        config.setFrequencyWindowMinutes(1);
        config.setBaselineHistoryDays(1);
        config.setMinBaselinePoints(5);
        config.setAnomalyCooldownMinutes(0);
        config.setSimilarityThreshold(0.7);
        config.setMaxTreeDepth(4);
        config.setMaxChildren(100);

        baselineManager = new BaselineManager(config);
        detector = new FrequencyAnomalyDetector(config, baselineManager);
    }

    @Test
    @DisplayName("频率异常检测在日志量突增3σ时正确触发异常标记")
    void shouldTriggerAnomalyWhenCountExceedsThreeSigma() throws Exception {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .withPaymentService()
                .build();

        buildStableBaseline(pattern, 10, 5);

        for (int i = 0; i < 100; i++) {
            baselineManager.processPattern(pattern);
        }

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern));

        assertThat(anomalies).isNotEmpty();
        AnomalyEvent anomaly = anomalies.get(0);
        assertThat(anomaly.getType()).isEqualTo(AnomalyEvent.AnomalyType.FREQUENCY);
        assertThat(anomaly.getPatternId()).isEqualTo(pattern.getId());
        assertThat(anomaly.getSigmaScore()).isGreaterThan(3.0);
        assertThat(anomaly.getDetails()).containsKey("currentCount");
        assertThat(anomaly.getDetails()).containsKey("baselineMean");
        assertThat(anomaly.getDetails()).containsKey("baselineStdDev");
    }

    @Test
    @DisplayName("正常波动范围内不触发异常")
    void shouldNotTriggerAnomalyForNormalFluctuations() throws Exception {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .withPaymentService()
                .build();

        buildStableBaseline(pattern, 10, 5);

        for (int i = 0; i < 12; i++) {
            baselineManager.processPattern(pattern);
        }

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern));

        assertThat(anomalies).isEmpty();
    }

    @Test
    @DisplayName("冷启动（无历史基线）时不触发异常")
    void shouldNotTriggerAnomalyDuringColdStart() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .withPaymentService()
                .build();

        baselineManager.processPattern(pattern);
        baselineManager.rotateWindow();

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern));

        assertThat(anomalies).isEmpty();
    }

    @Test
    @DisplayName("按sigma分数正确设置严重级别")
    void shouldSetSeverityBasedOnSigmaScore() throws Exception {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .withPaymentService()
                .build();

        buildStableBaseline(pattern, 10, 5);

        for (int i = 0; i < 100; i++) {
            baselineManager.processPattern(pattern);
        }

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern));

        assertThat(anomalies).isNotEmpty();
        AnomalyEvent anomaly = anomalies.get(0);
        double sigma = anomaly.getSigmaScore();

        if (sigma >= 5) {
            assertThat(anomaly.getSeverity()).isEqualTo(AnomalyEvent.Severity.CRITICAL);
        } else if (sigma >= 4) {
            assertThat(anomaly.getSeverity()).isEqualTo(AnomalyEvent.Severity.HIGH);
        } else if (sigma >= 3.5) {
            assertThat(anomaly.getSeverity()).isEqualTo(AnomalyEvent.Severity.MEDIUM);
        } else {
            assertThat(anomaly.getSeverity()).isEqualTo(AnomalyEvent.Severity.LOW);
        }
    }

    @Test
    @DisplayName("冷却期内不重复触发异常")
    void shouldNotTriggerDuplicateAnomalyWithinCooldown() throws Exception {
        config.setAnomalyCooldownMinutes(10);
        detector = new FrequencyAnomalyDetector(config, baselineManager);

        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .withPaymentService()
                .build();

        buildStableBaseline(pattern, 10, 5);

        for (int i = 0; i < 100; i++) {
            baselineManager.processPattern(pattern);
        }

        List<AnomalyEvent> firstDetection = detector.detect(List.of(pattern));
        assertThat(firstDetection).hasSize(1);

        List<AnomalyEvent> secondDetection = detector.detect(List.of(pattern));
        assertThat(secondDetection).isEmpty();
    }

    @Test
    @DisplayName("同时检测多个模式的异常")
    void shouldDetectAnomaliesForMultiplePatterns() throws Exception {
        LogPattern pattern1 = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .withPaymentService()
                .build();

        LogPattern pattern2 = LogPatternBuilder.aLogPattern()
                .withConnectionTimeoutTemplate()
                .asExisting()
                .withGatewayService()
                .build();

        buildStableBaseline(pattern1, 10, 5);
        buildStableBaseline(pattern2, 5, 5);

        for (int i = 0; i < 100; i++) {
            baselineManager.processPattern(pattern1);
        }
        for (int i = 0; i < 50; i++) {
            baselineManager.processPattern(pattern2);
        }

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern1, pattern2));

        assertThat(anomalies).hasSize(2);
        assertThat(anomalies).extracting("patternId")
                .containsExactlyInAnyOrder(pattern1.getId(), pattern2.getId());
    }

    @Test
    @DisplayName("异常事件包含完整的诊断信息")
    void shouldIncludeDiagnosticInformationInAnomaly() throws Exception {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .withPaymentService()
                .build();

        buildStableBaseline(pattern, 10, 5);

        for (int i = 0; i < 100; i++) {
            baselineManager.processPattern(pattern);
        }

        List<AnomalyEvent> anomalies = detector.detect(List.of(pattern));

        assertThat(anomalies).isNotEmpty();
        AnomalyEvent anomaly = anomalies.get(0);

        assertThat(anomaly.getPatternTemplate()).isEqualTo(pattern.getTemplate());
        assertThat(anomaly.getServiceName()).isEqualTo(pattern.getSampleService());
        assertThat(anomaly.getDetails()).containsKey("description");
        assertThat(anomaly.getDetails()).containsKey("deviationPercent");
        assertThat(anomaly.getDetails()).containsKey("windowMinutes");
        assertThat(anomaly.getDetails()).containsKey("patternTotalCount");
        assertThat(anomaly.getDetails()).containsKey("threshold");

        String description = (String) anomaly.getDetails().get("description");
        assertThat(description).contains("Pattern frequency spiked");
    }

    @Test
    @DisplayName("获取Top异常列表")
    void shouldReturnTopAnomalies() throws Exception {
        LogPattern pattern1 = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .withPaymentService()
                .build();

        LogPattern pattern2 = LogPatternBuilder.aLogPattern()
                .withConnectionTimeoutTemplate()
                .asExisting()
                .withGatewayService()
                .build();

        buildStableBaseline(pattern1, 10, 5);
        buildStableBaseline(pattern2, 5, 5);

        for (int i = 0; i < 100; i++) {
            baselineManager.processPattern(pattern1);
        }
        for (int i = 0; i < 30; i++) {
            baselineManager.processPattern(pattern2);
        }

        List<Map.Entry<String, Double>> topAnomalies = detector.getTopAnomalies(10);

        assertThat(topAnomalies).isNotEmpty();
        assertThat(topAnomalies).extracting("key")
                .contains(pattern1.getId(), pattern2.getId());

        double prevSigma = Double.MAX_VALUE;
        for (Map.Entry<String, Double> entry : topAnomalies) {
            assertThat(entry.getValue()).isLessThanOrEqualTo(prevSigma);
            prevSigma = entry.getValue();
        }
    }

    @Test
    @DisplayName("获取诊断信息")
    void shouldReturnDiagnostics() {
        Map<String, Object> diagnostics = detector.getDiagnostics();

        assertThat(diagnostics).containsKey("trackedPatterns");
        assertThat(diagnostics).containsEntry("sigmaThreshold", 3.0);
        assertThat(diagnostics).containsEntry("windowMinutes", 1);
        assertThat(diagnostics).containsEntry("cooldownMinutes", 0);
    }

    private void buildStableBaseline(LogPattern pattern, int baseCount, int numWindows) throws Exception {
        for (int window = 0; window < numWindows; window++) {
            int count = baseCount + (int) (Math.random() * 3);
            for (int i = 0; i < count; i++) {
                baselineManager.processPattern(pattern);
            }
            simulateTimePassed(pattern.getId(), 60_000L);
            baselineManager.rotateWindow();
        }
    }

    private void simulateTimePassed(String patternId, long millis) throws Exception {
        Field baselineMapField = BaselineManager.class.getDeclaredField("baselineMap");
        baselineMapField.setAccessible(true);
        Map<String, ?> baselineMap = (Map<String, ?>) baselineMapField.get(baselineManager);

        Object baseline = baselineMap.get(patternId);
        if (baseline == null) {
            return;
        }

        Field lastUpdateTimeField = baseline.getClass().getDeclaredField("lastUpdateTime");
        lastUpdateTimeField.setAccessible(true);
        AtomicLong lastUpdateTime = (AtomicLong) lastUpdateTimeField.get(baseline);

        lastUpdateTime.set(System.currentTimeMillis() - millis);
    }
}
