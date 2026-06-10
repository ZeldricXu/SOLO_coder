package com.loganalytics.pipeline.filter;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.pipeline.config.PipelineConfig;
import com.loganalytics.test.builder.LogEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogFilterTest {

    private PipelineConfig config;
    private LogFilter filter;

    @BeforeEach
    void setUp() {
        config = new PipelineConfig();
        config.setMinLogLevel(LogLevel.INFO);
        config.setNoiseKeywords(List.of("healthcheck", "debug_trace", "metrics_collector"));
        config.setHealthCheckExcludeEnabled(true);
        filter = new LogFilter(config);
    }

    @Test
    void shouldFilterOutDebugLogsWhenMinLevelIsInfo() {
        LogEvent debugEvent = LogEventBuilder.aLogEvent()
                .withLevelDebug()
                .withMessage("Debug message")
                .build();

        boolean accepted = filter.accept(debugEvent);

        assertThat(accepted).isFalse();
        assertThat(debugEvent.getTags()).containsEntry("filtered_reason", "level_below_threshold");
    }

    @Test
    void shouldAcceptInfoLogsWhenMinLevelIsInfo() {
        LogEvent infoEvent = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withMessage("Info message")
                .build();

        boolean accepted = filter.accept(infoEvent);

        assertThat(accepted).isTrue();
    }

    @Test
    void shouldAcceptErrorLogsWhenMinLevelIsInfo() {
        LogEvent errorEvent = LogEventBuilder.aLogEvent()
                .withLevelError()
                .withMessage("Error message")
                .build();

        boolean accepted = filter.accept(errorEvent);

        assertThat(accepted).isTrue();
    }

    @Test
    void shouldFilterOutLogsWithNoiseKeywords() {
        LogEvent noiseEvent = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withMessage("healthcheck endpoint called successfully")
                .build();

        boolean accepted = filter.accept(noiseEvent);

        assertThat(accepted).isFalse();
        assertThat(noiseEvent.getTags()).containsEntry("filtered_reason", "noise_keyword");
    }

    @Test
    void shouldFilterOutHealthCheckLogs() {
        LogEvent[] healthEvents = {
                LogEventBuilder.aLogEvent().withMessage("GET /actuator/health returned 200").build(),
                LogEventBuilder.aLogEvent().withMessage("healthcheck - service is up").build(),
                LogEventBuilder.aLogEvent().withMessage("Service alive and healthy").build(),
                LogEventBuilder.aLogEvent().withMessage("health check passed").build()
        };

        for (LogEvent event : healthEvents) {
            boolean accepted = filter.accept(event);
            assertThat(accepted).isFalse();
            assertThat(event.getTags()).containsEntry("filtered_reason", "health_check");
        }
    }

    @Test
    void shouldNotFilterWhenHealthCheckExcludeDisabled() {
        config.setHealthCheckExcludeEnabled(false);
        filter = new LogFilter(config);

        LogEvent healthEvent = LogEventBuilder.aLogEvent()
                .withMessage("healthcheck endpoint called")
                .build();

        boolean accepted = filter.accept(healthEvent);

        assertThat(accepted).isTrue();
    }

    @Test
    void shouldFilterOutCaseInsensitiveNoiseKeywords() {
        LogEvent upperCaseNoise = LogEventBuilder.aLogEvent()
                .withMessage("HEALTHCHECK success")
                .build();

        LogEvent mixedCaseNoise = LogEventBuilder.aLogEvent()
                .withMessage("HealthCheck completed")
                .build();

        assertThat(filter.accept(upperCaseNoise)).isFalse();
        assertThat(filter.accept(mixedCaseNoise)).isFalse();
    }

    @Test
    void shouldNotFilterNormalBusinessLogs() {
        LogEvent[] businessEvents = {
                LogEventBuilder.aLogEvent().withLevelInfo()
                        .withMessage("Order 12345 created successfully").build(),
                LogEventBuilder.aLogEvent().withLevelWarn()
                        .withMessage("High latency detected for payment API").build(),
                LogEventBuilder.aLogEvent().withLevelError()
                        .withMessage("Database connection failed").build()
        };

        for (LogEvent event : businessEvents) {
            boolean accepted = filter.accept(event);
            assertThat(accepted).isTrue();
        }
    }

    @Test
    void shouldTrackFilterStatistics() {
        int totalEvents = 100;
        int expectedFiltered = 30;

        for (int i = 0; i < totalEvents; i++) {
            LogEvent event;
            if (i < expectedFiltered) {
                event = LogEventBuilder.aLogEvent().withLevelDebug()
                        .withMessage("Debug log " + i).build();
            } else {
                event = LogEventBuilder.aLogEvent().withLevelInfo()
                        .withMessage("Info log " + i).build();
            }
            filter.accept(event);
        }

        assertThat(filter.getTotalProcessed()).isEqualTo(totalEvents);
        assertThat(filter.getTotalFiltered()).isEqualTo(expectedFiltered);
        assertThat(filter.getFilterRate()).isCloseTo(30.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void shouldFilterWithCustomMinLevel() {
        config.setMinLogLevel(LogLevel.WARN);
        filter = new LogFilter(config);

        LogEvent infoEvent = LogEventBuilder.aLogEvent().withLevelInfo().build();
        LogEvent warnEvent = LogEventBuilder.aLogEvent().withLevelWarn().build();
        LogEvent errorEvent = LogEventBuilder.aLogEvent().withLevelError().build();

        assertThat(filter.accept(infoEvent)).isFalse();
        assertThat(filter.accept(warnEvent)).isTrue();
        assertThat(filter.accept(errorEvent)).isTrue();
    }

    @Test
    void shouldNotFilterWhenMinLevelIsNull() {
        config.setMinLogLevel(null);
        filter = new LogFilter(config);

        LogEvent traceEvent = LogEventBuilder.aLogEvent()
                .withLevel(LogLevel.TRACE)
                .build();

        assertThat(filter.accept(traceEvent)).isTrue();
    }

    @Test
    void shouldFilterLogsWithNoiseKeywordInRawMessage() {
        LogEvent event = new LogEvent();
        event.setRawMessage("Processing healthcheck request");
        event.setMessage("Processing request");

        boolean accepted = filter.accept(event);

        assertThat(accepted).isFalse();
    }

    @Test
    void shouldNotFilterLogsWithNullMessage() {
        LogEvent nullMessageEvent = new LogEvent();
        nullMessageEvent.setMessage(null);
        nullMessageEvent.setRawMessage(null);
        nullMessageEvent.setLevel(LogLevel.INFO);

        boolean accepted = filter.accept(nullMessageEvent);

        assertThat(accepted).isTrue();
    }

    @Test
    void shouldHandleMixedLevelAndNoiseFiltering() {
        config.setMinLogLevel(LogLevel.INFO);
        config.setNoiseKeywords(List.of("verbose"));
        filter = new LogFilter(config);

        LogEvent verboseDebug = LogEventBuilder.aLogEvent()
                .withLevelDebug()
                .withMessage("verbose debug log")
                .build();

        LogEvent verboseInfo = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withMessage("verbose info log")
                .build();

        assertThat(filter.accept(verboseDebug)).isFalse();
        assertThat(verboseDebug.getTags()).containsEntry("filtered_reason", "level_below_threshold");

        assertThat(filter.accept(verboseInfo)).isFalse();
        assertThat(verboseInfo.getTags()).containsEntry("filtered_reason", "noise_keyword");
    }

    @Test
    void shouldFilterAllBelowThresholdInBatch() {
        int debugCount = 50;
        int infoCount = 50;

        for (int i = 0; i < debugCount; i++) {
            filter.accept(LogEventBuilder.aLogEvent().withLevelDebug().build());
        }
        for (int i = 0; i < infoCount; i++) {
            filter.accept(LogEventBuilder.aLogEvent().withLevelInfo().build());
        }

        assertThat(filter.getTotalProcessed()).isEqualTo(debugCount + infoCount);
        assertThat(filter.getTotalFiltered()).isEqualTo(debugCount);
    }
}
