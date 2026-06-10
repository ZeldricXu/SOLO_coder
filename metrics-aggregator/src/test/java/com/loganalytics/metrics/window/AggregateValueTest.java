package com.loganalytics.metrics.window;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.metrics.window.WindowedAggregator.AggregateValue;
import com.loganalytics.test.builder.LogEventBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AggregateValue - 聚合值计算")
class AggregateValueTest {

    @Test
    @DisplayName("1分钟窗口内按服务分组的计数准确")
    void shouldAccuratelyCountByServiceWithinOneMinuteWindow() {
        AggregateValue aggregate = new AggregateValue();

        for (int i = 0; i < 10; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withPaymentService()
                    .withLevelInfo()
                    .withMessage("Payment processed successfully")
                    .withTimestamp(Instant.now())
                    .build();
            aggregate.add(event);
        }

        for (int i = 0; i < 5; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withPaymentService()
                    .withLevelError()
                    .withMessage("Payment failed")
                    .withTimestamp(Instant.now())
                    .build();
            aggregate.add(event);
        }

        for (int i = 0; i < 3; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withPaymentService()
                    .withLevelWarn()
                    .withMessage("Payment processing slow")
                    .withTimestamp(Instant.now())
                    .build();
            aggregate.add(event);
        }

        assertThat(aggregate.count).isEqualTo(18);
        assertThat(aggregate.errorCount).isEqualTo(5);
        assertThat(aggregate.warnCount).isEqualTo(3);
        assertThat(aggregate.getErrorRate()).isEqualTo(5.0 / 18.0);
    }

    @Test
    @DisplayName("正确统计不同级别的日志计数")
    void shouldCountLogsByLevelCorrectly() {
        AggregateValue aggregate = new AggregateValue();

        LogEvent errorEvent = LogEventBuilder.aLogEvent()
                .withLevelError()
                .withMessage("Database connection failed")
                .build();
        aggregate.add(errorEvent);

        LogEvent warnEvent = LogEventBuilder.aLogEvent()
                .withLevelWarn()
                .withMessage("High memory usage")
                .build();
        aggregate.add(warnEvent);

        LogEvent infoEvent = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withMessage("Request processed")
                .build();
        aggregate.add(infoEvent);

        LogEvent debugEvent = LogEventBuilder.aLogEvent()
                .withLevelDebug()
                .withMessage("Debug info")
                .build();
        aggregate.add(debugEvent);

        assertThat(aggregate.count).isEqualTo(4);
        assertThat(aggregate.errorCount).isEqualTo(1);
        assertThat(aggregate.warnCount).isEqualTo(1);
    }

    @Test
    @DisplayName("正确统计处理的字节数")
    void shouldCountBytesProcessed() {
        AggregateValue aggregate = new AggregateValue();

        String message1 = "Short message";
        String message2 = "A slightly longer message that has more bytes";

        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withMessage(message1)
                .build();
        aggregate.add(event1);

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withMessage(message2)
                .build();
        aggregate.add(event2);

        long expectedBytes = message1.getBytes().length + message2.getBytes().length;
        assertThat(aggregate.bytesProcessed).isEqualTo(expectedBytes);
    }

    @Test
    @DisplayName("正确追踪唯一模式和模式计数")
    void shouldTrackUniquePatternsAndPatternCounts() {
        AggregateValue aggregate = new AggregateValue();

        for (int i = 0; i < 5; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withPatternId("pattern-1")
                    .withMessage("User login")
                    .build();
            aggregate.add(event);
        }

        for (int i = 0; i < 3; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withPatternId("pattern-2")
                    .withMessage("Database query")
                    .build();
            aggregate.add(event);
        }

        LogEvent event = LogEventBuilder.aLogEvent()
                .withPatternId("pattern-3")
                .withMessage("API call")
                .build();
        aggregate.add(event);

        assertThat(aggregate.uniquePatterns).hasSize(3);
        assertThat(aggregate.uniquePatterns).contains("pattern-1", "pattern-2", "pattern-3");
        assertThat(aggregate.patternCounts).containsEntry("pattern-1", 5L);
        assertThat(aggregate.patternCounts).containsEntry("pattern-2", 3L);
        assertThat(aggregate.patternCounts).containsEntry("pattern-3", 1L);
    }

    @Test
    @DisplayName("获取Top K模式按频率排序")
    void shouldGetTopKPatternsSortedByFrequency() {
        AggregateValue aggregate = new AggregateValue();

        for (int i = 0; i < 10; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withPatternId("pattern-top")
                    .build();
            aggregate.add(event);
        }

        for (int i = 0; i < 5; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withPatternId("pattern-middle")
                    .build();
            aggregate.add(event);
        }

        LogEvent event = LogEventBuilder.aLogEvent()
                .withPatternId("pattern-bottom")
                .build();
        aggregate.add(event);

        List<Map.Entry<String, Long>> top2 = aggregate.getTopPatterns(2);

        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).getKey()).isEqualTo("pattern-top");
        assertThat(top2.get(0).getValue()).isEqualTo(10L);
        assertThat(top2.get(1).getKey()).isEqualTo("pattern-middle");
        assertThat(top2.get(1).getValue()).isEqualTo(5L);
    }

    @Test
    @DisplayName("正确合并两个聚合值")
    void shouldMergeTwoAggregateValues() {
        AggregateValue agg1 = new AggregateValue();
        for (int i = 0; i < 5; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withLevelError()
                    .withPatternId("pattern-1")
                    .withMessage("Error in service")
                    .withTimestamp(Instant.now().minusSeconds(30))
                    .build();
            agg1.add(event);
        }

        AggregateValue agg2 = new AggregateValue();
        for (int i = 0; i < 3; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withLevelWarn()
                    .withPatternId("pattern-2")
                    .withMessage("Warning in service")
                    .withTimestamp(Instant.now())
                    .build();
            agg2.add(event);
        }

        AggregateValue merged = agg1.merge(agg2);

        assertThat(merged.count).isEqualTo(8);
        assertThat(merged.errorCount).isEqualTo(5);
        assertThat(merged.warnCount).isEqualTo(3);
        assertThat(merged.uniquePatterns).hasSize(2);
        assertThat(merged.firstTimestamp).isLessThan(merged.lastTimestamp);
    }

    @Test
    @DisplayName("正确计算每秒事件数(EPS)")
    void shouldCalculateEventsPerSecond() {
        AggregateValue aggregate = new AggregateValue();
        Duration windowSize = Duration.ofMinutes(1);

        Instant start = Instant.now();
        for (int i = 0; i < 60; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withMessage("Event " + i)
                    .withTimestamp(start.plusSeconds(i))
                    .build();
            aggregate.add(event);
        }

        double eps = aggregate.getEps(windowSize);
        assertThat(eps).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    @DisplayName("正确计算错误率")
    void shouldCalculateErrorRate() {
        AggregateValue aggregate = new AggregateValue();

        for (int i = 0; i < 80; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withLevelInfo()
                    .build();
            aggregate.add(event);
        }

        for (int i = 0; i < 15; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withLevelWarn()
                    .build();
            aggregate.add(event);
        }

        for (int i = 0; i < 5; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withLevelError()
                    .build();
            aggregate.add(event);
        }

        assertThat(aggregate.getErrorRate()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("空聚合值返回零错误率")
    void shouldReturnZeroErrorRateForEmptyAggregate() {
        AggregateValue aggregate = new AggregateValue();
        assertThat(aggregate.getErrorRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("正确追踪时间戳范围")
    void shouldTrackTimestampRange() {
        AggregateValue aggregate = new AggregateValue();

        Instant earliest = Instant.now().minusSeconds(60);
        Instant latest = Instant.now();

        LogEvent earlyEvent = LogEventBuilder.aLogEvent()
                .withMessage("Early event")
                .withTimestamp(earliest)
                .build();
        aggregate.add(earlyEvent);

        LogEvent lateEvent = LogEventBuilder.aLogEvent()
                .withMessage("Late event")
                .withTimestamp(latest)
                .build();
        aggregate.add(lateEvent);

        assertThat(aggregate.firstTimestamp).isEqualTo(earliest.toEpochMilli());
        assertThat(aggregate.lastTimestamp).isEqualTo(latest.toEpochMilli());
    }

    @Test
    @DisplayName("null消息不影响字节计数")
    void shouldHandleNullMessageGracefully() {
        AggregateValue aggregate = new AggregateValue();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withMessage(null)
                .build();
        aggregate.add(event);

        assertThat(aggregate.count).isEqualTo(1);
        assertThat(aggregate.bytesProcessed).isEqualTo(0);
    }

    @Test
    @DisplayName("null时间戳使用当前时间")
    void shouldUseCurrentTimeForNullTimestamp() {
        AggregateValue aggregate = new AggregateValue();

        LogEvent event = LogEventBuilder.aLogEvent()
                .withTimestamp(null)
                .build();
        aggregate.add(event);

        long now = System.currentTimeMillis();
        assertThat(aggregate.firstTimestamp).isLessThanOrEqualTo(now);
        assertThat(aggregate.lastTimestamp).isLessThanOrEqualTo(now);
    }
}
