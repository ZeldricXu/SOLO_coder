package com.loganalytics.pipeline.parse;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.pipeline.config.PipelineConfig;
import com.loganalytics.test.builder.LogEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogParserTest {

    private PipelineConfig config;
    private LogParser parser;

    @BeforeEach
    void setUp() {
        config = new PipelineConfig();
        config.setGrokPatterns(List.of(
                "%{COMMONAPACHELOG}",
                "%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{DATA:service} %{GREEDYDATA:message}"
        ));
        parser = new LogParser(config);
    }

    @Test
    void shouldParseApacheCommonLogFormatUsingGrok() {
        String apacheLog = "192.168.1.100 - - [15/Jan/2024:10:30:45 +0000] \"GET /api/v1/users HTTP/1.1\" 200 1234";

        LogEvent event = LogEventBuilder.aLogEvent()
                .withRawMessage(apacheLog)
                .withMessage(apacheLog)
                .build();

        LogEvent parsed = parser.parse(event);

        assertThat(parsed.getFields()).containsKey("clientip");
        assertThat(parsed.getFields().get("clientip")).isEqualTo("192.168.1.100");
        assertThat(parsed.getFields()).containsKey("timestamp");
        assertThat(parsed.getFields()).containsKey("request");
        assertThat(parsed.getFields().get("request")).isEqualTo("GET /api/v1/users HTTP/1.1");
        assertThat(parsed.getFields()).containsKey("response");
        assertThat(parsed.getFields().get("response")).isEqualTo("200");
        assertThat(parsed.getFields()).containsKey("bytes");
        assertThat(parsed.getFields().get("bytes")).isEqualTo("1234");
        assertThat(parsed.getTimestamp()).isNotNull();
        assertThat(parsed.getTags()).containsEntry("parsed", "true");
    }

    @Test
    void shouldParseStructuredLogWithTimestampLevelService() {
        String structuredLog = "2024-01-15T10:30:45.123Z INFO payment-service Request processed in 45ms";

        LogEvent event = LogEventBuilder.aLogEvent()
                .withRawMessage(structuredLog)
                .withMessage(structuredLog)
                .build();

        LogEvent parsed = parser.parse(event);

        assertThat(parsed.getTimestamp()).isNotNull();
        assertThat(parsed.getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(parsed.getServiceName()).isEqualTo("payment-service");
        assertThat(parsed.getMessage()).isEqualTo("Request processed in 45ms");
    }

    @Test
    void shouldParseLogWithBracketLevelFormat() {
        String logLine = "2024-01-15T10:30:45.123Z [ERROR] Database connection failed";

        LogEvent event = LogEventBuilder.aLogEvent()
                .withRawMessage(logLine)
                .withMessage(logLine)
                .build();

        LogEvent parsed = parser.parse(event);

        assertThat(parsed.getTimestamp()).isNotNull();
        assertThat(parsed.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(parsed.getMessage()).isEqualTo("Database connection failed");
    }

    @Test
    void shouldExtractTraceIdFromLogMessage() {
        String logLine = "2024-01-15T10:30:45Z INFO service - Processing request traceId=abc123def456";

        LogEvent event = LogEventBuilder.aLogEvent()
                .withRawMessage(logLine)
                .withMessage(logLine)
                .build();

        LogEvent parsed = parser.parse(event);

        assertThat(parsed.getTraceId()).isEqualTo("abc123def456");
    }

    @Test
    void shouldExtractErrorCodeFromLogMessage() {
        String logLine = "2024-01-15T10:30:45Z ERROR service - Failed with error_code: AUTH_001";

        LogEvent event = LogEventBuilder.aLogEvent()
                .withRawMessage(logLine)
                .withMessage(logLine)
                .build();

        LogEvent parsed = parser.parse(event);

        assertThat(parsed.getFields()).containsEntry("error_code", "AUTH_001");
    }

    @Test
    void shouldHandleBlankRawMessageGracefully() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withRawMessage("")
                .build();

        LogEvent parsed = parser.parse(event);

        assertThat(parsed).isNotNull();
        assertThat(parsed.getTimestamp()).isNotNull();
    }

    @Test
    void shouldFallbackToBasicFieldExtractionWhenRegexFails() {
        String unstructuredLog = "Something went wrong! This is an ERROR message";

        LogEvent event = LogEventBuilder.aLogEvent()
                .withRawMessage(unstructuredLog)
                .withMessage(unstructuredLog)
                .build();

        LogEvent parsed = parser.parse(event);

        assertThat(parsed.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(parsed.getMessage()).isEqualTo(unstructuredLog);
        assertThat(parsed.getTags()).containsEntry("parsed", "false");
    }

    @Test
    void shouldParseMultipleApacheLogFormats() {
        String[] apacheLogs = {
                "10.0.0.1 - admin [15/Jan/2024:10:00:00 +0000] \"POST /api/login HTTP/1.1\" 200 567",
                "172.16.0.50 - - [15/Jan/2024:10:00:01 +0000] \"GET /api/products HTTP/1.1\" 404 234",
                "192.168.1.1 - user123 [15/Jan/2024:10:00:02 +0000] \"PUT /api/orders/123 HTTP/1.1\" 500 890"
        };

        for (int i = 0; i < apacheLogs.length; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withRawMessage(apacheLogs[i])
                    .withMessage(apacheLogs[i])
                    .build();

            LogEvent parsed = parser.parse(event);

            assertThat(parsed.getFields()).containsKey("clientip");
            assertThat(parsed.getFields()).containsKey("response");
        }
    }

    @Test
    void shouldPreserveExistingNonNullFields() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withPaymentService()
                .withHostname("host-123")
                .withSourceIp("10.0.0.1")
                .withRawMessage("2024-01-15T10:30:45Z INFO other-service - Override test")
                .withMessage("2024-01-15T10:30:45Z INFO other-service - Override test")
                .build();

        LogEvent parsed = parser.parse(event);

        assertThat(parsed.getServiceName()).isEqualTo("payment-service");
        assertThat(parsed.getHostname()).isEqualTo("host-123");
        assertThat(parsed.getSourceIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void shouldSetCurrentTimestampWhenParsingFails() {
        String logWithoutTimestamp = "Some random log without timestamp";

        LogEvent event = LogEventBuilder.aLogEvent()
                .withRawMessage(logWithoutTimestamp)
                .withMessage(logWithoutTimestamp)
                .build();

        LogEvent parsed = parser.parse(event);

        assertThat(parsed.getTimestamp()).isNotNull();
    }

    @Test
    void shouldExtractTraceIdWithVariousFormats() {
        String[] logLines = {
                "2024-01-15T10:30:45Z INFO service - traceId: abc123 processing",
                "2024-01-15T10:30:45Z INFO service - TraceID=def456 processing",
                "2024-01-15T10:30:45Z INFO service - trace_id=ghi789 processing",
                "2024-01-15T10:30:45Z INFO service - [traceId=jkl012] processing"
        };

        for (String logLine : logLines) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withRawMessage(logLine)
                    .withMessage(logLine)
                    .build();

            LogEvent parsed = parser.parse(event);

            assertThat(parsed.getTraceId()).isNotNull();
        }
    }
}
