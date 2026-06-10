package com.loganalytics.agent.multiline;

import com.loganalytics.agent.config.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiLineMergerTest {

    private AgentConfig config;

    @BeforeEach
    void setUp() {
        config = new AgentConfig();
        config.setMultiLineEnabled(true);
        config.setMultiLinePattern("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}");
        config.setMultiLineNegate(false);
        config.setMultiLineMatch("after");
    }

    @Test
    void shouldMergeExceptionStacktraceIntoSingleEvent() {
        MultiLineMerger merger = new MultiLineMerger(config);
        List<String> mergedLines = new ArrayList<>();
        List<Integer> lineCounts = new ArrayList<>();

        MultiLineMerger.LineHandler handler = (line, count) -> {
            mergedLines.add(line);
            lineCounts.add(count);
        };

        String[] exceptionLines = {
                "2024-01-15T10:30:45Z ERROR payment-service - Unhandled exception processing request",
                "java.lang.NullPointerException: Cannot invoke method on null object",
                "    at com.payment.OrderService.processOrder(OrderService.java:123)",
                "    at com.payment.OrderController.createOrder(OrderController.java:45)",
                "    at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)",
                "Caused by: java.lang.IllegalArgumentException: Invalid order ID",
                "    at com.payment.OrderValidator.validate(OrderValidator.java:67)",
                "    ... 2 more"
        };

        for (String line : exceptionLines) {
            merger.processLine(line, handler);
        }

        merger.flush(handler);

        assertThat(mergedLines).hasSize(1);
        assertThat(lineCounts).hasSize(1);
        assertThat(lineCounts.get(0)).isEqualTo(8);
        assertThat(mergedLines.get(0)).contains("NullPointerException");
        assertThat(mergedLines.get(0)).contains("Caused by:");
        assertThat(mergedLines.get(0)).contains("OrderService.java:123");
        assertThat(mergedLines.get(0)).contains("at sun.reflect");
    }

    @Test
    void shouldSeparateMultipleExceptionEvents() {
        MultiLineMerger merger = new MultiLineMerger(config);
        List<String> mergedLines = new ArrayList<>();
        List<Integer> lineCounts = new ArrayList<>();

        MultiLineMerger.LineHandler handler = (line, count) -> {
            mergedLines.add(line);
            lineCounts.add(count);
        };

        String[] lines = {
                "2024-01-15T10:30:45Z ERROR service1 - First error",
                "    at com.service.Class1.method1(Class1.java:100)",
                "    at com.service.Class1.method2(Class1.java:200)",
                "2024-01-15T10:30:46Z ERROR service1 - Second error",
                "    at com.service.Class2.method3(Class2.java:300)",
                "2024-01-15T10:30:47Z INFO service1 - Normal log between exceptions"
        };

        for (String line : lines) {
            merger.processLine(line, handler);
        }

        merger.flush(handler);

        assertThat(mergedLines).hasSize(3);
        assertThat(lineCounts).containsExactly(3, 2, 1);
        assertThat(mergedLines.get(0)).contains("First error");
        assertThat(mergedLines.get(1)).contains("Second error");
        assertThat(mergedLines.get(2)).contains("Normal log");
    }

    @Test
    void shouldMergeLinesWithLeadingWhitespace() {
        config.setMultiLinePattern("^\\s+|^Caused by:|^\\tat ");
        config.setMultiLineNegate(true);

        MultiLineMerger merger = new MultiLineMerger(config);
        List<String> mergedLines = new ArrayList<>();
        List<Integer> lineCounts = new ArrayList<>();

        MultiLineMerger.LineHandler handler = (line, count) -> {
            mergedLines.add(line);
            lineCounts.add(count);
        };

        String[] lines = {
                "2024-01-15T10:00:00Z INFO service - Starting transaction",
                "   Query: SELECT * FROM orders WHERE status = 'NEW'",
                "   Parameters: [2024-01-15, 50]",
                "2024-01-15T10:00:01Z INFO service - Transaction committed"
        };

        for (String line : lines) {
            merger.processLine(line, handler);
        }

        merger.flush(handler);

        assertThat(mergedLines).hasSize(2);
        assertThat(lineCounts).containsExactly(3, 1);
        assertThat(mergedLines.get(0)).contains("SELECT * FROM orders");
        assertThat(mergedLines.get(0)).contains("Parameters:");
    }

    @Test
    void shouldReturnCorrectMultiLineCount() {
        MultiLineMerger merger = new MultiLineMerger(config);
        List<Integer> lineCounts = new ArrayList<>();

        MultiLineMerger.LineHandler handler = (line, count) -> lineCounts.add(count);

        String[] singleLineEvents = {
                "2024-01-15T10:00:00Z INFO service - Event 1",
                "2024-01-15T10:00:01Z INFO service - Event 2",
                "2024-01-15T10:00:02Z INFO service - Event 3"
        };

        for (String line : singleLineEvents) {
            merger.processLine(line, handler);
        }

        merger.flush(handler);

        assertThat(lineCounts).containsExactly(1, 1, 1);
    }

    @Test
    void shouldFlushPendingLinesOnTimeout() throws InterruptedException {
        MultiLineMerger merger = new MultiLineMerger(config);
        List<String> mergedLines = new ArrayList<>();

        MultiLineMerger.LineHandler handler = (line, count) -> mergedLines.add(line);

        String[] partialException = {
                "2024-01-15T10:30:45Z ERROR service - Exception occurred",
                "    at com.service.Class.method(Class.java:100)"
        };

        for (String line : partialException) {
            merger.processLine(line, handler);
        }

        assertThat(merger.hasPending()).isTrue();
        assertThat(mergedLines).isEmpty();

        Thread.sleep(6000);

        merger.checkTimeout(handler);

        assertThat(merger.hasPending()).isFalse();
        assertThat(mergedLines).hasSize(1);
    }

    @Test
    void shouldHandleEmptyLinesInStacktrace() {
        MultiLineMerger merger = new MultiLineMerger(config);
        List<String> mergedLines = new ArrayList<>();
        List<Integer> lineCounts = new ArrayList<>();

        MultiLineMerger.LineHandler handler = (line, count) -> {
            mergedLines.add(line);
            lineCounts.add(count);
        };

        String[] lines = {
                "2024-01-15T10:30:45Z ERROR service - Error with empty lines in trace",
                "java.lang.RuntimeException: Test exception",
                "",
                "    at com.service.Class.method(Class.java:100)",
                "",
                "Caused by: java.lang.IllegalStateException: Invalid state",
                "    at com.service.Validator.validate(Validator.java:50)"
        };

        for (String line : lines) {
            merger.processLine(line, handler);
        }

        merger.flush(handler);

        assertThat(mergedLines).hasSize(1);
        assertThat(lineCounts.get(0)).isEqualTo(7);
        assertThat(mergedLines.get(0)).contains("\n\n    at com.service.Class.method");
    }

    @Test
    void shouldFlushWhenMaxLinesReached() {
        config.setMultiLinePattern("^CONTINUATION:");
        config.setMultiLineNegate(true);

        MultiLineMerger merger = new MultiLineMerger(config);
        List<String> mergedLines = new ArrayList<>();
        List<Integer> lineCounts = new ArrayList<>();

        MultiLineMerger.LineHandler handler = (line, count) -> {
            mergedLines.add(line);
            lineCounts.add(count);
        };

        List<String> lines = new ArrayList<>();
        lines.add("2024-01-15T10:00:00Z INFO service - Start of very long message");
        for (int i = 0; i < 1500; i++) {
            lines.add("CONTINUATION: line " + i);
        }
        lines.add("2024-01-15T10:00:01Z INFO service - Next event");

        for (String line : lines) {
            merger.processLine(line, handler);
        }

        merger.flush(handler);

        assertThat(lineCounts.get(0)).isEqualTo(1000);
        assertThat(lineCounts.get(1)).isEqualTo(501);
        assertThat(lineCounts.get(2)).isEqualTo(1);
    }

    @Test
    void shouldNotMergeWhenMultiLineDisabled() {
        config.setMultiLineEnabled(false);
        config.setMultiLinePattern("^\\d{4}-\\d{2}-\\d{2}");

        MultiLineMerger merger = new MultiLineMerger(config);
        List<String> mergedLines = new ArrayList<>();
        List<Integer> lineCounts = new ArrayList<>();

        MultiLineMerger.LineHandler handler = (line, count) -> {
            mergedLines.add(line);
            lineCounts.add(count);
        };

        String[] lines = {
                "2024-01-15T10:00:00Z INFO service - Line 1",
                "    Continuation line",
                "2024-01-15T10:00:01Z INFO service - Line 2"
        };

        for (String line : lines) {
            if (config.isMultiLineEnabled()) {
                merger.processLine(line, handler);
            } else {
                handler.onCompleteLine(line, 1);
            }
        }

        assertThat(mergedLines).hasSize(3);
        assertThat(lineCounts).containsExactly(1, 1, 1);
    }

    @Test
    void shouldPreserveOriginalLineFormatting() {
        MultiLineMerger merger = new MultiLineMerger(config);
        List<String> mergedLines = new ArrayList<>();

        MultiLineMerger.LineHandler handler = (line, count) -> mergedLines.add(line);

        String[] lines = {
                "2024-01-15T10:30:45Z ERROR service - JSON parsing error",
                "  Raw payload: {\"id\": 123, \"name\": \"test\",",
                "                \"nested\": {\"key\": \"value\"}}",
                "  Error: Unexpected character at position 25"
        };

        for (String line : lines) {
            merger.processLine(line, handler);
        }

        merger.flush(handler);

        assertThat(mergedLines).hasSize(1);
        String merged = mergedLines.get(0);
        assertThat(merged).contains("\"id\": 123");
        assertThat(merged).contains("                \"nested\":");
        assertThat(merged).contains("\n  Error: Unexpected");
    }

    @Test
    void hasPendingShouldReturnTrueWhenBufferNotEmpty() {
        MultiLineMerger merger = new MultiLineMerger(config);
        MultiLineMerger.LineHandler handler = (line, count) -> {};

        assertThat(merger.hasPending()).isFalse();

        merger.processLine("2024-01-15T10:00:00Z INFO service - Test", handler);
        assertThat(merger.hasPending()).isTrue();

        merger.processLine("    continuation", handler);
        assertThat(merger.hasPending()).isTrue();

        merger.flush(handler);
        assertThat(merger.hasPending()).isFalse();
    }
}
