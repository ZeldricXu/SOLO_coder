package com.loganalytics.metrics.window;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.MetricPoint;
import com.loganalytics.metrics.config.MetricsConfig;
import com.loganalytics.test.builder.LogEventBuilder;
import com.loganalytics.test.builder.MetricPointBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WindowedAggregator - 窗口聚合")
class WindowedAggregatorTest {

    private WindowedAggregator aggregator;
    private MetricsConfig config;

    @BeforeEach
    void setUp() {
        config = new MetricsConfig();
        config.setWindows(List.of(
                new MetricsConfig.WindowConfig(
                        "1min_tumbling",
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        MetricsConfig.WindowConfig.WindowType.TUMBLING
                )
        ));
        config.setTopKSize(20);
        config.setTopKUpdateInterval(Duration.ofSeconds(30));
        config.setTimescaleUrl("jdbc:postgresql://localhost:5432/loganalytics");
        config.setTimescaleUser("postgres");
        config.setTimescalePassword("postgres");
        config.setTimescalePoolSize(2);
        config.setRawDataRetentionDays(7);
        config.setEnableContinuousAggregation(false);

        aggregator = new WindowedAggregator(config);
    }

    @Test
    @DisplayName("记录指标并正确存储")
    void shouldRecordAndStoreMetrics() {
        MetricPoint metric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .build();

        aggregator.recordMetric(metric);

        double currentValue = aggregator.getCurrentMetricValue(
                "log_count", "payment-service", Duration.ofMinutes(5)
        );
        assertThat(currentValue).isEqualTo(100.0);
    }

    @Test
    @DisplayName("正确计算指定时间范围内的指标平均值")
    void shouldCalculateAverageMetricValueOverTimeWindow() {
        Instant now = Instant.now();

        MetricPoint metric1 = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .withTimestamp(now.minusSeconds(30))
                .build();

        MetricPoint metric2 = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(200.0)
                .withTimestamp(now.minusSeconds(15))
                .build();

        MetricPoint metric3 = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(300.0)
                .withTimestamp(now)
                .build();

        aggregator.recordMetric(metric1);
        aggregator.recordMetric(metric2);
        aggregator.recordMetric(metric3);

        double average = aggregator.getCurrentMetricValue(
                "log_count", "payment-service", Duration.ofMinutes(1)
        );
        assertThat(average).isCloseTo(200.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    @DisplayName("正确计算模式频率总和")
    void shouldCalculatePatternFrequencySum() {
        Instant now = Instant.now();

        for (int i = 0; i < 5; i++) {
            MetricPoint metric = MetricPointBuilder.aMetricPoint()
                    .withLogCountMetric()
                    .withOneMinuteWindow()
                    .withPaymentService()
                    .withValue(10.0)
                    .withTimestamp(now.minusSeconds(i * 10))
                    .build();
            aggregator.recordMetric(metric);
        }

        double frequency = aggregator.getPatternFrequency(
                "payment-service", Duration.ofMinutes(1), List.of()
        );
        assertThat(frequency).isEqualTo(50.0);
    }

    @Test
    @DisplayName("无数据时返回零值")
    void shouldReturnZeroForNoData() {
        double value = aggregator.getCurrentMetricValue(
                "unknown_metric", "unknown-service", Duration.ofMinutes(5)
        );
        assertThat(value).isEqualTo(0.0);

        double frequency = aggregator.getPatternFrequency(
                "unknown-service", Duration.ofMinutes(5), List.of()
        );
        assertThat(frequency).isEqualTo(0.0);

        double errorRate = aggregator.getErrorRate(
                "unknown-service", Duration.ofMinutes(5)
        );
        assertThat(errorRate).isEqualTo(0.0);
    }

    @Test
    @DisplayName("超出时间范围的指标不被计算")
    void shouldNotIncludeMetricsOutsideTimeWindow() {
        Instant now = Instant.now();

        MetricPoint recentMetric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .withTimestamp(now.minusSeconds(30))
                .build();

        MetricPoint oldMetric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(500.0)
                .withTimestamp(now.minusSeconds(120))
                .build();

        aggregator.recordMetric(recentMetric);
        aggregator.recordMetric(oldMetric);

        double value = aggregator.getCurrentMetricValue(
                "log_count", "payment-service", Duration.ofMinutes(1)
        );
        assertThat(value).isEqualTo(100.0);
    }

    @Test
    @DisplayName("正确计算错误率")
    void shouldCalculateErrorRate() {
        Instant now = Instant.now();

        MetricPoint errorRateMetric = MetricPointBuilder.aMetricPoint()
                .withErrorRateMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(0.05)
                .withTimestamp(now)
                .build();

        aggregator.recordMetric(errorRateMetric);

        double errorRate = aggregator.getErrorRate(
                "payment-service", Duration.ofMinutes(1)
        );
        assertThat(errorRate).isEqualTo(0.05);
    }

    @Test
    @DisplayName("最多保留指定数量的指标")
    void shouldKeepMaxMetricsPerKey() {
        for (int i = 0; i < 150; i++) {
            MetricPoint metric = MetricPointBuilder.aMetricPoint()
                    .withLogCountMetric()
                    .withOneMinuteWindow()
                    .withPaymentService()
                    .withValue((double) i)
                    .withTimestamp(Instant.now().minusSeconds(i))
                    .build();
            aggregator.recordMetric(metric);
        }

        double sum = aggregator.getPatternFrequency(
                "payment-service", Duration.ofHours(1), List.of()
        );

        double expectedSum = 0;
        for (int i = 0; i < 100; i++) {
            expectedSum += i;
        }

        assertThat(sum).isEqualTo(expectedSum);
    }

    @Test
    @DisplayName("延迟到达的日志在允许范围内被正确处理")
    void shouldHandleLateArrivingEventsWithinAllowedRange() {
        Instant now = Instant.now();

        MetricPoint onTimeMetric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .withTimestamp(now)
                .build();

        MetricPoint lateMetric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(50.0)
                .withTimestamp(now.minusSeconds(30))
                .build();

        aggregator.recordMetric(onTimeMetric);
        aggregator.recordMetric(lateMetric);

        double sum = aggregator.getPatternFrequency(
                "payment-service", Duration.ofMinutes(1), List.of()
        );
        assertThat(sum).isEqualTo(150.0);
    }

    @Test
    @DisplayName("不同服务的指标相互隔离")
    void shouldIsolateMetricsForDifferentServices() {
        MetricPoint paymentMetric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .build();

        MetricPoint gatewayMetric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withGatewayService()
                .withValue(200.0)
                .build();

        aggregator.recordMetric(paymentMetric);
        aggregator.recordMetric(gatewayMetric);

        double paymentValue = aggregator.getCurrentMetricValue(
                "log_count", "payment-service", Duration.ofMinutes(5)
        );
        double gatewayValue = aggregator.getCurrentMetricValue(
                "log_count", "gateway-service", Duration.ofMinutes(5)
        );

        assertThat(paymentValue).isEqualTo(100.0);
        assertThat(gatewayValue).isEqualTo(200.0);
    }

    @Test
    @DisplayName("不同指标名称相互隔离")
    void shouldIsolateDifferentMetricNames() {
        MetricPoint countMetric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .build();

        MetricPoint errorMetric = MetricPointBuilder.aMetricPoint()
                .withErrorRateMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(0.05)
                .build();

        aggregator.recordMetric(countMetric);
        aggregator.recordMetric(errorMetric);

        double countValue = aggregator.getCurrentMetricValue(
                "log_count", "payment-service", Duration.ofMinutes(5)
        );
        double errorValue = aggregator.getCurrentMetricValue(
                "error_rate", "payment-service", Duration.ofMinutes(5)
        );

        assertThat(countValue).isEqualTo(100.0);
        assertThat(errorValue).isEqualTo(0.05);
    }

    @Test
    @DisplayName("获取基线频率返回默认值")
    void shouldReturnDefaultBaselineFrequency() {
        double baseline = aggregator.getBaselineFrequency(
                "payment-service", Duration.ofMinutes(5)
        );
        assertThat(baseline).isEqualTo(10.0);
    }

    @Test
    @DisplayName("聚合键正确从LogEvent创建")
    void shouldCreateAggregationKeyFromLogEvent() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withPaymentService()
                .withLevelError()
                .withField("errorCode", "E500")
                .withPatternId("pattern-123")
                .build();

        WindowedAggregator.AggregationKey key = new WindowedAggregator.AggregationKey(
                event.getServiceName(),
                event.getLevel(),
                event.getField("errorCode"),
                event.getPatternId()
        );

        assertThat(key.getServiceName()).isEqualTo("payment-service");
        assertThat(key.getLevel()).isEqualTo(com.loganalytics.common.model.LogLevel.ERROR);
        assertThat(key.getErrorCode()).isEqualTo("E500");
        assertThat(key.getPatternId()).isEqualTo("pattern-123");
    }

    @Test
    @DisplayName("聚合值正确累加多个LogEvent")
    void shouldAggregateMultipleLogEventsCorrectly() {
        WindowedAggregator.AggregateValue aggregate = new WindowedAggregator.AggregateValue();

        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withPaymentService()
                .withLevelInfo()
                .withMessage("Info message")
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withPaymentService()
                .withLevelError()
                .withMessage("Error message")
                .build();

        aggregate.add(event1);
        aggregate.add(event2);

        assertThat(aggregate.count).isEqualTo(2);
        assertThat(aggregate.errorCount).isEqualTo(1);
        assertThat(aggregate.getErrorRate()).isEqualTo(0.5);
    }
}
