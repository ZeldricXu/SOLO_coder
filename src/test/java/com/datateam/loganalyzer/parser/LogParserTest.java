package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("日志解析器单元测试")
class LogParserTest {

    private String testResourcesPath;

    @BeforeEach
    void setUp() {
        testResourcesPath = "src/test/resources/sample-logs/";
    }

    @Test
    @DisplayName("正常路径：Log4j单行日志解析字段正确")
    void testLog4jSingleLineParsing() throws IOException {
        List<String> lines = FileUtils.readAllLines(testResourcesPath + "log4j/single-line.log");
        LogParser parser = LogParserFactory.createParser(LogFormat.LOG4J);

        List<LogEvent> events = parser.parseAll(lines);

        assertThat(events).hasSize(10);

        LogEvent firstEvent = events.get(0);
        assertThat(firstEvent.getTimestamp()).isEqualTo(Instant.parse("2024-06-01T02:23:45.123Z"));
        assertThat(firstEvent.getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(firstEvent.getLogger()).isEqualTo("com.example.service.UserService");
        assertThat(firstEvent.getMessage()).contains("User login successful");

        LogEvent errorEvent = events.get(3);
        assertThat(errorEvent.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(errorEvent.getLogger()).isEqualTo("com.example.payment.PaymentService");
        assertThat(errorEvent.getMessage()).contains("Payment failed");
    }

    @Test
    @DisplayName("正常路径：Syslog RFC5424带结构化数据解析正确")
    void testSyslogRfc5424Parsing() throws IOException {
        List<String> lines = FileUtils.readAllLines(testResourcesPath + "syslog/rfc5424-structured.log");
        LogParser parser = LogParserFactory.createParser(LogFormat.SYSLOG);

        List<LogEvent> events = parser.parseAll(lines);

        assertThat(events).hasSize(6);

        LogEvent firstEvent = events.get(0);
        assertThat(firstEvent.getTimestamp()).isNotNull();
        assertThat(firstEvent.getHost()).isEqualTo("app-server-01");
        assertThat(firstEvent.getService()).isEqualTo("user-service");
        assertThat(firstEvent.getMessage()).contains("User login successful");

        LogEvent errorEvent = events.get(4);
        assertThat(errorEvent.getHost()).isEqualTo("db-server-01");
        assertThat(errorEvent.getMessage()).contains("duplicate key value violates unique constraint");
    }

    @Test
    @DisplayName("正常路径：JSON嵌套lines解析正确")
    void testJsonNestedParsing() throws IOException {
        List<String> lines = FileUtils.readAllLines(testResourcesPath + "json/nested-json.log");
        LogParser parser = LogParserFactory.createParser(LogFormat.JSON_LINES);

        List<LogEvent> events = parser.parseAll(lines);

        assertThat(events).hasSize(5);

        LogEvent firstEvent = events.get(0);
        assertThat(firstEvent.getTimestamp()).isEqualTo(Instant.parse("2024-06-01T10:23:45.123+08:00"));
        assertThat(firstEvent.getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(firstEvent.getService()).isEqualTo("user-service");
        assertThat(firstEvent.getMessage()).isEqualTo("User login successful");
        assertThat(firstEvent.getField("userId")).isEqualTo("1001");

        LogEvent errorEvent = events.get(3);
        assertThat(errorEvent.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(errorEvent.getService()).isEqualTo("payment-service");
        assertThat(errorEvent.getField("error.code")).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("正常路径：Java异常堆栈多行合并正确")
    void testJavaStackTraceMerging() throws IOException {
        List<String> rawLines = FileUtils.readAllLines(testResourcesPath + "multiline/java-stacktrace.log");
        List<String> mergedLines = MultiLineMerger.mergeLines(rawLines);

        assertThat(mergedLines).hasSize(5);

        LogParser parser = LogParserFactory.createParser(LogFormat.LOG4J);
        MultiLineMerger merger = new MultiLineMerger(parser);
        List<LogEvent> events = merger.processLines(rawLines);

        assertThat(events).hasSize(5);

        LogEvent errorEvent = events.get(1);
        assertThat(errorEvent.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(errorEvent.getMessage()).contains("Payment processing failed");
        assertThat(errorEvent.getStackTrace()).isNotNull();
        assertThat(errorEvent.getStackTrace()).contains("java.lang.RuntimeException: Payment gateway timeout");
        assertThat(errorEvent.getStackTrace()).contains("Caused by: java.net.SocketTimeoutException");
        assertThat(errorEvent.getStackTrace()).contains("... 12 more");
        assertThat(errorEvent.getErrorType()).isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("异常路径：无法匹配的日志行归类为unknown并保留原始行")
    void testUnparseableLinesClassifiedAsUnknown() throws IOException {
        List<String> lines = FileUtils.readAllLines(testResourcesPath + "edge-cases/unparseable-lines.log");
        LogParser parser = LogParserFactory.autoDetect(lines, "test-service");

        List<LogEvent> events = parser.parseAll(lines);

        assertThat(events).hasSize(lines.size());

        long unknownCount = events.stream()
                .filter(e -> e.getLevel() == LogLevel.UNKNOWN)
                .count();
        assertThat(unknownCount).isGreaterThan(0);

        for (LogEvent event : events) {
            assertThat(event.getRawLine()).isNotNull();
            assertThat(event.getRawLine()).isNotEmpty();
        }

        List<LogEvent> unknownEvents = events.stream()
                .filter(e -> e.getLevel() == LogLevel.UNKNOWN)
                .toList();
        assertThat(unknownEvents.get(0).getMessage())
                .isEqualTo("This is some random garbage text that doesn't match any format");
    }

    @Test
    @DisplayName("边界场景：单行日志超过1MB时截断处理不崩")
    void testVeryLongLineHandling() throws IOException {
        List<String> lines = FileUtils.readAllLines(testResourcesPath + "edge-cases/very-long-line.log");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).length()).isGreaterThan(1024 * 1024);

        LogParser parser = LogParserFactory.createParser(LogFormat.LOG4J);

        LogEvent event = parser.parse(lines.get(0));

        assertThat(event).isNotNull();
        assertThat(event.getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(event.getLogger()).isEqualTo("com.example.service.VeryLongMessageService");
        assertThat(event.getRawLine()).isNotNull();
        assertThat(event.getMessage()).isNotNull();
    }

    @Test
    @DisplayName("自动识别日志格式")
    void testAutoDetectFormat() throws IOException {
        List<String> log4jLines = FileUtils.readAllLines(testResourcesPath + "log4j/single-line.log");
        LogFormat log4jFormat = LogParserFactory.detectFormat(log4jLines);
        assertThat(log4jFormat).isEqualTo(LogFormat.LOG4J);

        List<String> jsonLines = FileUtils.readAllLines(testResourcesPath + "json/nested-json.log");
        LogFormat jsonFormat = LogParserFactory.detectFormat(jsonLines);
        assertThat(jsonFormat).isEqualTo(LogFormat.JSON_LINES);

        List<String> syslogLines = FileUtils.readAllLines(testResourcesPath + "syslog/rfc5424-structured.log");
        LogFormat syslogFormat = LogParserFactory.detectFormat(syslogLines);
        assertThat(syslogFormat).isEqualTo(LogFormat.SYSLOG);
    }

    @ParameterizedTest
    @CsvSource({
        "2024-06-01 10:23:45.123 INFO  Test - Message, INFO",
        "2024-06-01 10:23:45 DEBUG Test - Message, DEBUG",
        "2024-06-01 10:23:45 WARN Test - Message, WARN",
        "2024-06-01 10:23:45 ERROR Test - Message, ERROR",
        "2024-06-01 10:23:45 FATAL Test - Message, FATAL",
        "Random garbage line with no level, UNKNOWN"
    })
    @DisplayName("日志级别解析正确")
    void testLogLevelParsing(String line, String expectedLevel) {
        LogParser parser = LogParserFactory.createParser(LogFormat.LOG4J);
        LogEvent event = parser.parse(line);

        assertThat(event).isNotNull();
        assertThat(event.getLevel()).isEqualTo(LogLevel.valueOf(expectedLevel));
    }

    @Test
    @DisplayName("空行和null行处理")
    void testEmptyAndNullLines() {
        LogParser parser = LogParserFactory.createParser(LogFormat.LOG4J);

        assertThat(parser.parse(null)).isNull();
        assertThat(parser.parse("")).isNull();
        assertThat(parser.parse("   ")).isNull();
    }
}
