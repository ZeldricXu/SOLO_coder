package com.loganalytics.detector.baseline;

import com.loganalytics.common.model.LogPattern;
import com.loganalytics.detector.config.DetectorConfig;
import com.loganalytics.test.builder.LogPatternBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BaselineManager - 基线管理")
class BaselineManagerTest {

    private BaselineManager baselineManager;
    private DetectorConfig config;

    @BeforeEach
    void setUp() {
        config = new DetectorConfig();
        config.setSigmaThreshold(3.0);
        config.setFrequencyWindowMinutes(1);
        config.setBaselineHistoryDays(1);
        config.setMinBaselinePoints(5);
        config.setAnomalyCooldownMinutes(5);
        config.setSimilarityThreshold(0.7);
        config.setMaxTreeDepth(4);
        config.setMaxChildren(100);

        baselineManager = new BaselineManager(config);
    }

    @Test
    @DisplayName("处理模式并记录计数")
    void shouldProcessPatternAndRecordCount() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        baselineManager.processPattern(pattern);
        baselineManager.processPattern(pattern);
        baselineManager.processPattern(pattern);

        Map<String, Object> info = baselineManager.getBaselineInfo(pattern.getId());
        assertThat(info).isNotEmpty();
        assertThat(info.get("currentCount")).isEqualTo(3L);
    }

    @Test
    @DisplayName("窗口轮换后计算统计数据")
    void shouldComputeStatsAfterWindowRotation() throws Exception {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        for (int i = 0; i < 10; i++) {
            baselineManager.processPattern(pattern);
        }

        simulateTimePassed(pattern.getId(), 60_000L);
        baselineManager.rotateWindow();

        double[] stats = baselineManager.getStats(pattern.getId());
        assertThat(stats[2]).isEqualTo(0.0);
    }

    @Test
    @DisplayName("积累足够数据点后统计数据有效")
    void shouldHaveValidStatsAfterSufficientDataPoints() throws Exception {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        for (int window = 0; window < 6; window++) {
            for (int i = 0; i < 5 + window; i++) {
                baselineManager.processPattern(pattern);
            }
            simulateTimePassed(pattern.getId(), 60_000L);
            baselineManager.rotateWindow();
        }

        Map<String, Object> info = baselineManager.getBaselineInfo(pattern.getId());
        assertThat(info).containsEntry("statsValid", true);

        double[] stats = baselineManager.getStats(pattern.getId());
        assertThat(stats[0]).isGreaterThan(0);
        assertThat(stats[1]).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("统计数据不足时返回零值")
    void shouldReturnZeroStatsWhenInsufficientData() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        baselineManager.processPattern(pattern);
        baselineManager.rotateWindow();

        double[] stats = baselineManager.getStats(pattern.getId());
        assertThat(stats).containsExactly(0.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("未知模式返回零统计")
    void shouldReturnZeroStatsForUnknownPattern() {
        double[] stats = baselineManager.getStats("unknown-pattern-id");
        assertThat(stats).containsExactly(0.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("获取跟踪的模式数量")
    void shouldReturnTrackedPatternCount() {
        LogPattern pattern1 = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        LogPattern pattern2 = LogPatternBuilder.aLogPattern()
                .withConnectionTimeoutTemplate()
                .asExisting()
                .build();

        baselineManager.processPattern(pattern1);
        baselineManager.processPattern(pattern2);

        assertThat(baselineManager.getTrackedPatternCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("获取Top异常按sigma分数排序")
    void shouldGetTopAnomaliesSortedBySigma() throws Exception {
        LogPattern highVolumePattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        LogPattern lowVolumePattern = LogPatternBuilder.aLogPattern()
                .withConnectionTimeoutTemplate()
                .asExisting()
                .build();

        for (int window = 0; window < 6; window++) {
            for (int i = 0; i < 10; i++) {
                baselineManager.processPattern(highVolumePattern);
            }
            for (int i = 0; i < 2; i++) {
                baselineManager.processPattern(lowVolumePattern);
            }
            simulateTimePassed(highVolumePattern.getId(), 60_000L);
            simulateTimePassed(lowVolumePattern.getId(), 60_000L);
            baselineManager.rotateWindow();
        }

        for (int i = 0; i < 100; i++) {
            baselineManager.processPattern(highVolumePattern);
        }

        List<Map.Entry<String, Double>> topAnomalies = baselineManager.getTopAnomalies(5);
        assertThat(topAnomalies).isNotEmpty();

        double prevSigma = Double.MAX_VALUE;
        for (Map.Entry<String, Double> entry : topAnomalies) {
            assertThat(entry.getValue()).isLessThanOrEqualTo(prevSigma);
            prevSigma = entry.getValue();
        }
    }

    @Test
    @DisplayName("频率异常检测在日志量突增3σ时正确触发异常标记")
    void shouldDetectThreeSigmaAnomalyWhenCountSpikes() throws Exception {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        for (int window = 0; window < 6; window++) {
            for (int i = 0; i < 5; i++) {
                baselineManager.processPattern(pattern);
            }
            simulateTimePassed(pattern.getId(), 60_000L);
            baselineManager.rotateWindow();
        }

        for (int i = 0; i < 50; i++) {
            baselineManager.processPattern(pattern);
        }

        double[] stats = baselineManager.getStats(pattern.getId());
        double mean = stats[0];
        double stdDev = stats[1];
        long currentCount = (long) stats[2];

        double sigma = (currentCount - mean) / stdDev;
        assertThat(sigma).isGreaterThan(3.0);
        assertThat(baselineManager.isFrequencyAnomaly(pattern.getId(), currentCount)).isTrue();
    }

    @Test
    @DisplayName("标准差为零时当前计数大于均值触发异常")
    void shouldDetectAnomalyWhenStdDevIsZeroAndCountExceedsMean() throws Exception {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        for (int window = 0; window < 6; window++) {
            for (int i = 0; i < 5; i++) {
                baselineManager.processPattern(pattern);
            }
            simulateTimePassed(pattern.getId(), 60_000L);
            baselineManager.rotateWindow();
        }

        for (int i = 0; i < 10; i++) {
            baselineManager.processPattern(pattern);
        }

        double sigma = baselineManager.getSigmaScore(pattern.getId(), 10);
        assertThat(sigma).isGreaterThan(config.getSigmaThreshold());
        assertThat(baselineManager.isFrequencyAnomaly(pattern.getId(), 10)).isTrue();
    }

    @Test
    @DisplayName("正常范围内的波动不触发异常")
    void shouldNotDetectAnomalyForNormalFluctuations() throws Exception {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        for (int window = 0; window < 6; window++) {
            int count = 8 + window;
            for (int i = 0; i < count; i++) {
                baselineManager.processPattern(pattern);
            }
            simulateTimePassed(pattern.getId(), 60_000L);
            baselineManager.rotateWindow();
        }

        for (int i = 0; i < 12; i++) {
            baselineManager.processPattern(pattern);
        }

        double[] stats = baselineManager.getStats(pattern.getId());
        long currentCount = (long) stats[2];
        double sigma = baselineManager.getSigmaScore(pattern.getId(), currentCount);

        assertThat(sigma).isLessThanOrEqualTo(3.0);
        assertThat(baselineManager.isFrequencyAnomaly(pattern.getId(), currentCount)).isFalse();
    }

    @Test
    @DisplayName("冷启动时基线信息正确")
    void shouldHaveCorrectBaselineInfoDuringColdStart() {
        LogPattern pattern = LogPatternBuilder.aLogPattern()
                .withUserLoginTemplate()
                .asExisting()
                .build();

        baselineManager.processPattern(pattern);

        Map<String, Object> info = baselineManager.getBaselineInfo(pattern.getId());
        assertThat(info).containsEntry("statsValid", false);
        assertThat(info).containsEntry("mean", 0.0);
        assertThat(info).containsEntry("stdDev", 0.0);
        assertThat(info).containsEntry("currentCount", 1L);
        assertThat(info).containsKey("historySlots");
        assertThat(info).containsKey("windowMinutes");
        assertThat(info).containsKey("recentHistory");
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
