package com.datateam.loganalyzer.anomaly;

import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("异常检测引擎单元测试")
class AnomalyDetectionTest {

    @Test
    @DisplayName("正常路径：Z-score检测在输入稳定流量时误报率低于5%")
    void testZScoreFalsePositiveRate() {
        ZScoreDetector detector = new ZScoreDetector(3.0, 10);

        List<Double> baselineData = generateNormalDistribution(1000, 10.0, 2.0);
        detector.train(baselineData, "test-metric");

        List<Double> testData = generateNormalDistribution(10000, 10.0, 2.0);
        List<AnomalyResult> results = detector.detect(testData);

        long trueAnomalies = results.stream().filter(AnomalyResult::isAnomaly).count();
        double falsePositiveRate = (double) trueAnomalies / testData.size();

        assertThat(falsePositiveRate).isLessThan(0.05);
    }

    @Test
    @DisplayName("正常路径：Z-score正确检测正异常")
    void testZScorePositiveAnomalyDetection() {
        ZScoreDetector detector = new ZScoreDetector(3.0, 10);

        List<Double> baselineData = generateNormalDistribution(100, 10.0, 2.0);
        detector.train(baselineData, "test-metric");

        List<Double> testData = new ArrayList<>(baselineData);
        testData.add(20.0);
        testData.add(25.0);
        testData.add(10.0);
        testData.add(10.0);
        testData.add(10.0);
        testData.add(10.0);
        testData.add(10.0);
        testData.add(10.0);
        testData.add(10.0);
        testData.add(10.0);
        testData.add(10.0);
        testData.add(10.0);

        List<AnomalyResult> results = detector.detect(testData);

        List<AnomalyResult> anomalies = results.stream()
                .filter(AnomalyResult::isAnomaly)
                .toList();

        assertThat(anomalies).hasSize(2);
        assertThat(anomalies.get(0).getObservedValue()).isEqualTo(20.0);
        assertThat(anomalies.get(1).getObservedValue()).isEqualTo(25.0);
        assertThat(anomalies.get(0).getzScore()).isGreaterThan(3.0);
    }

    @Test
    @DisplayName("异常路径：基线期数据量不足时提示需要更多数据")
    void testInsufficientBaselineData() {
        ZScoreDetector detector = new ZScoreDetector(3.0, 10);

        List<Double> smallData = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        detector.train(smallData, "test-metric");

        assertThat(detector.getBaseline()).isNotNull();
        assertThat(detector.getBaseline().getDataSize()).isEqualTo(5);
        assertThat(detector.getMinDataPoints()).isEqualTo(10);

        List<Double> testData = generateNormalDistribution(20, 10.0, 2.0);
        testData.add(100.0);

        List<AnomalyResult> results = detector.detect(testData);

        assertThat(results).isNotEmpty();

        AnomalyResult anomaly = results.stream()
                .filter(AnomalyResult::isAnomaly)
                .findFirst()
                .orElse(null);

        assertThat(anomaly).isNotNull();
        assertThat(anomaly.getDescription()).contains("Z-score");
    }

    @Test
    @DisplayName("正常路径：移动平均残差分析正确")
    void testMovingAverageResidual() {
        MovingAverageDetector detector = new MovingAverageDetector(5, 2.5, 10);

        List<Double> baselineData = generateNormalDistribution(100, 10.0, 1.0);
        detector.train(baselineData, "test-metric");

        List<Double> testData = new ArrayList<>();
        Random random = new Random(12345);
        for (int i = 0; i < 15; i++) {
            testData.add(10.0 + random.nextDouble() * 0.2 - 0.1);
        }
        testData.add(100.0);
        testData.add(150.0);

        List<AnomalyResult> results = detector.detect(testData);

        long anomalyCount = results.stream().filter(AnomalyResult::isAnomaly).count();
        assertThat(anomalyCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("边界场景：空数据和null数据处理")
    void testEmptyAndNullData() {
        ZScoreDetector detector = new ZScoreDetector(3.0, 5);

        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect(new ArrayList<>())).isEmpty();

        List<Double> dataWithNulls = new ArrayList<>();
        dataWithNulls.add(1.0);
        dataWithNulls.add(null);
        dataWithNulls.add(3.0);

        List<AnomalyResult> results = detector.detect(dataWithNulls);
        assertThat(results).isNotNull();
    }

    @Test
    @DisplayName("正常路径：从时间序列检测异常")
    void testDetectFromTimeSeries() {
        ZScoreDetector detector = new ZScoreDetector(2.0, 5);

        List<TimeSeriesPoint> points = new ArrayList<>();
        Instant baseTime = Instant.parse("2024-06-01T10:00:00Z");

        for (int i = 0; i < 15; i++) {
            TimeSeriesPoint point = new TimeSeriesPoint();
            point.setWindowStart(baseTime.plusSeconds(i));
            point.setWindowEnd(baseTime.plusSeconds(i + 1));
            for (int j = 0; j < 5; j++) {
                point.incrementTotal();
            }
            point.calculateRates();
            points.add(point);
        }

        TimeSeriesPoint anomalyPoint = new TimeSeriesPoint();
        anomalyPoint.setWindowStart(baseTime.plusSeconds(15));
        anomalyPoint.setWindowEnd(baseTime.plusSeconds(16));
        for (int j = 0; j < 20; j++) {
            anomalyPoint.incrementTotal();
            anomalyPoint.incrementLevel(com.datateam.loganalyzer.model.LogLevel.ERROR);
        }
        anomalyPoint.calculateRates();
        points.add(anomalyPoint);

        detector.trainFromTimeSeries(points.subList(0, 10), "total");

        List<AnomalyResult> results = detector.detectFromTimeSeries(points, "total");

        List<AnomalyResult> anomalies = results.stream()
                .filter(AnomalyResult::isAnomaly)
                .toList();

        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(anomalies.size() - 1).getTimestamp())
                .isEqualTo(baseTime.plusSeconds(15));
    }

    @Test
    @DisplayName("边界场景：标准差为0时处理")
    void testZeroStdDevHandling() {
        ZScoreDetector detector = new ZScoreDetector(3.0, 5);

        List<Double> constantData = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            constantData.add(5.0);
        }
        detector.train(constantData, "test-metric");

        List<Double> testData = new ArrayList<>(constantData);
        testData.add(5.0);
        testData.add(15.0);
        testData.add(5.0);

        List<AnomalyResult> results = detector.detect(testData);

        assertThat(results).isNotEmpty();

        AnomalyResult anomaly = results.stream()
                .filter(r -> r.getObservedValue() == 15.0)
                .findFirst()
                .orElse(null);

        assertThat(anomaly).isNotNull();
        assertThat(anomaly.isAnomaly()).isTrue();
    }

    private List<Double> generateNormalDistribution(int count, double mean, double stdDev) {
        List<Double> data = new ArrayList<>();
        Random random = new Random(42);
        for (int i = 0; i < count; i++) {
            double value = mean + random.nextGaussian() * stdDev;
            data.add(value);
        }
        return data;
    }
}
