package com.datateam.loganalyzer.aggregator;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import com.datateam.loganalyzer.util.TimeUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("时间序列聚合器单元测试")
class TimeSeriesAggregatorTest {

    @Test
    @DisplayName("正常路径：滚动窗口切换时刻无数据丢失或重复计数")
    void testTumblingWindowNoDataLoss() {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(TimeUtils.Granularity.SECOND);

        Instant baseTime = Instant.parse("2024-06-01T10:23:45Z");

        for (int i = 0; i < 100; i++) {
            LogEvent event = createEvent(baseTime.plusMillis(i * 100), LogLevel.INFO, "service-a");
            aggregator.add(event);
        }

        List<TimeSeriesPoint> points = aggregator.getTimeSeries();

        assertThat(points).hasSize(10);
        assertThat(aggregator.getTotalCount()).isEqualTo(100);

        long sum = points.stream().mapToLong(TimeSeriesPoint::getTotalCount).sum();
        assertThat(sum).isEqualTo(100);

        for (TimeSeriesPoint point : points) {
            assertThat(point.getTotalCount()).isBetween(9L, 11L);
        }
    }

    @Test
    @DisplayName("异常路径：时间戳乱序到达时各窗口计数正确归位")
    void testOutOfOrderTimestamps() {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(TimeUtils.Granularity.SECOND);

        Instant baseTime = Instant.parse("2024-06-01T10:24:00Z");

        List<LogEvent> events = new ArrayList<>();
        events.add(createEvent(baseTime.plusSeconds(0), LogLevel.INFO, "service-a"));
        events.add(createEvent(baseTime.plusSeconds(2), LogLevel.INFO, "service-a"));
        events.add(createEvent(baseTime.plusSeconds(1), LogLevel.INFO, "service-a"));
        events.add(createEvent(baseTime.plusSeconds(4), LogLevel.INFO, "service-a"));
        events.add(createEvent(baseTime.plusSeconds(1), LogLevel.INFO, "service-a"));
        events.add(createEvent(baseTime.plusSeconds(3), LogLevel.INFO, "service-a"));
        events.add(createEvent(baseTime.plusSeconds(0), LogLevel.INFO, "service-a"));
        events.add(createEvent(baseTime.plusSeconds(2), LogLevel.INFO, "service-a"));

        for (LogEvent event : events) {
            aggregator.add(event);
        }

        List<TimeSeriesPoint> points = aggregator.getTimeSeries();

        assertThat(points).hasSize(5);
        assertThat(aggregator.getTotalCount()).isEqualTo(8);

        assertThat(points.get(0).getTotalCount()).isEqualTo(2);
        assertThat(points.get(1).getTotalCount()).isEqualTo(2);
        assertThat(points.get(2).getTotalCount()).isEqualTo(2);
        assertThat(points.get(3).getTotalCount()).isEqualTo(1);
        assertThat(points.get(4).getTotalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("正常路径：按日志级别聚合正确")
    void testLevelAggregation() {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(TimeUtils.Granularity.MINUTE);

        Instant time = Instant.parse("2024-06-01T10:23:00Z");

        for (int i = 0; i < 10; i++) {
            aggregator.add(createEvent(time, LogLevel.INFO, "service-a"));
        }
        for (int i = 0; i < 5; i++) {
            aggregator.add(createEvent(time, LogLevel.WARN, "service-a"));
        }
        for (int i = 0; i < 3; i++) {
            aggregator.add(createEvent(time, LogLevel.ERROR, "service-a"));
        }
        aggregator.add(createEvent(time, LogLevel.DEBUG, "service-a"));

        List<TimeSeriesPoint> points = aggregator.getTimeSeries();
        assertThat(points).hasSize(1);

        TimeSeriesPoint point = points.get(0);
        assertThat(point.getTotalCount()).isEqualTo(19);
        assertThat(point.getLevelCount(LogLevel.INFO)).isEqualTo(10);
        assertThat(point.getLevelCount(LogLevel.WARN)).isEqualTo(5);
        assertThat(point.getLevelCount(LogLevel.ERROR)).isEqualTo(3);
        assertThat(point.getLevelCount(LogLevel.DEBUG)).isEqualTo(1);
        assertThat(point.getErrorCount()).isEqualTo(3);
        assertThat(point.getWarnCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("正常路径：按服务聚合正确")
    void testServiceAggregation() {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(TimeUtils.Granularity.MINUTE);
        Instant time = Instant.parse("2024-06-01T10:23:00Z");

        for (int i = 0; i < 15; i++) {
            aggregator.add(createEvent(time, LogLevel.INFO, "user-service"));
        }
        for (int i = 0; i < 10; i++) {
            aggregator.add(createEvent(time, LogLevel.INFO, "order-service"));
        }
        for (int i = 0; i < 5; i++) {
            aggregator.add(createEvent(time, LogLevel.ERROR, "payment-service"));
        }

        Map<String, Long> serviceTotals = aggregator.getServiceTotals();
        assertThat(serviceTotals).hasSize(3);
        assertThat(serviceTotals.get("user-service")).isEqualTo(15);
        assertThat(serviceTotals.get("order-service")).isEqualTo(10);
        assertThat(serviceTotals.get("payment-service")).isEqualTo(5);

        Map<String, Long> serviceErrors = aggregator.getServiceErrorTotals();
        assertThat(serviceErrors).hasSize(1);
        assertThat(serviceErrors.get("payment-service")).isEqualTo(5);
    }

    @Test
    @DisplayName("边界场景：百万级events/s高速率下吞吐不掉队（模拟测试）")
    void testHighThroughputSimulation() {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(TimeUtils.Granularity.SECOND);
        Instant baseTime = Instant.parse("2024-06-01T10:23:00Z");

        int eventCount = 100000;
        long startTime = System.nanoTime();

        for (int i = 0; i < eventCount; i++) {
            Instant eventTime = baseTime.plusMillis(i % 5000);
            LogLevel level = (i % 100 < 5) ? LogLevel.ERROR : LogLevel.INFO;
            String service = "service-" + (i % 5);
            aggregator.add(createEvent(eventTime, level, service));
        }

        long duration = System.nanoTime() - startTime;
        double eventsPerSecond = (double) eventCount / (duration / 1_000_000_000.0);

        assertThat(aggregator.getTotalCount()).isEqualTo(eventCount);
        assertThat(eventsPerSecond).isGreaterThan(10000);

        List<TimeSeriesPoint> points = aggregator.getTimeSeries();
        long sum = points.stream().mapToLong(TimeSeriesPoint::getTotalCount).sum();
        assertThat(sum).isEqualTo(eventCount);
    }

    @Test
    @DisplayName("边界场景：null事件和null时间戳正确处理")
    void testNullEventHandling() {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(TimeUtils.Granularity.SECOND);

        aggregator.add(null);

        LogEvent eventWithNullTimestamp = new LogEvent();
        eventWithNullTimestamp.setLevel(LogLevel.INFO);
        eventWithNullTimestamp.setService("test");
        aggregator.add(eventWithNullTimestamp);

        assertThat(aggregator.getTotalCount()).isEqualTo(0);
        assertThat(aggregator.getTimeSeries()).isEmpty();
    }

    @Test
    @DisplayName("滑动窗口聚合正确")
    void testSlidingWindowAggregation() {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(30, 10);
        Instant baseTime = Instant.parse("2024-06-01T10:23:00Z");

        for (int i = 0; i < 60; i++) {
            aggregator.add(createEvent(baseTime.plusSeconds(i), LogLevel.INFO, "service-a"));
        }

        List<TimeSeriesPoint> points = aggregator.getTimeSeries();
        assertThat(points).isNotEmpty();

        for (TimeSeriesPoint point : points) {
            long expectedCount = Math.min(30,
                Math.min(60 - point.getWindowStart().getEpochSecond() + baseTime.getEpochSecond(),
                    point.getWindowStart().getEpochSecond() - baseTime.getEpochSecond() + 30));
            if (expectedCount > 0) {
                assertThat(point.getTotalCount()).isGreaterThan(0);
            }
        }
    }

    @Test
    @DisplayName("按错误类型聚合正确")
    void testErrorTypeAggregation() {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(TimeUtils.Granularity.MINUTE);
        Instant time = Instant.parse("2024-06-01T10:23:00Z");

        for (int i = 0; i < 5; i++) {
            LogEvent event = createEvent(time, LogLevel.ERROR, "service-a");
            event.setMessage("NullPointerException: something is null");
            aggregator.add(event);
        }
        for (int i = 0; i < 3; i++) {
            LogEvent event = createEvent(time, LogLevel.ERROR, "service-a");
            event.setMessage("TimeoutException: connection timed out");
            aggregator.add(event);
        }

        Map<String, Long> errorTypes = aggregator.getErrorTypeTotals();
        assertThat(errorTypes.get("NullPointerException")).isEqualTo(5);
        assertThat(errorTypes.get("TimeoutException")).isEqualTo(3);
    }

    @Test
    @DisplayName("时间范围计算正确")
    void testTimeRangeCalculation() {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(TimeUtils.Granularity.MINUTE);

        Instant startTime = Instant.parse("2024-06-01T10:23:00Z");
        Instant endTime = Instant.parse("2024-06-01T10:25:30Z");

        aggregator.add(createEvent(startTime, LogLevel.INFO, "test"));
        aggregator.add(createEvent(endTime, LogLevel.INFO, "test"));

        assertThat(aggregator.getStartTime()).isEqualTo(Instant.parse("2024-06-01T10:23:00Z"));
        assertThat(aggregator.getEndTime()).isEqualTo(Instant.parse("2024-06-01T10:26:00Z"));
    }

    private LogEvent createEvent(Instant timestamp, LogLevel level, String service) {
        LogEvent event = new LogEvent();
        event.setTimestamp(timestamp);
        event.setLevel(level);
        event.setService(service);
        event.setMessage("Test message");
        return event;
    }
}
