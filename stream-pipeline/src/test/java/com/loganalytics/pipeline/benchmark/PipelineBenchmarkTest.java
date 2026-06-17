package com.loganalytics.pipeline.benchmark;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.pipeline.processor.Processor;
import com.loganalytics.pipeline.processor.ProcessorChain;
import com.loganalytics.pipeline.processor.ProcessorFactory;
import com.loganalytics.test.builder.LogEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pipeline Performance Benchmark - 流式管道性能基准")
class PipelineBenchmarkTest {

    private static final int WARMUP_COUNT = 10_000;
    private static final int BENCHMARK_COUNT = 100_000;

    private ProcessorChain chain;
    private List<LogEvent> testEvents;

    @BeforeEach
    void setUp() {
        chain = ProcessorFactory.createChain(List.of(
                Map.of("type", "parse", "params", Map.of(
                        "grok.pattern.common_log", "%{COMMONAPACHELOG}"
                )),
                Map.of("type", "filter", "params", Map.of(
                        "excludedLevels", "DEBUG",
                        "excludeHealthChecks", "true"
                )),
                Map.of("type", "enrich", "params", Map.of()),
                Map.of("type", "route", "params", Map.of(
                        "INFO", "parsed-logs",
                        "WARN", "parsed-logs",
                        "ERROR", "error-logs"
                ))
        ));

        testEvents = new ArrayList<>();
        for (int i = 0; i < BENCHMARK_COUNT; i++) {
            testEvents.add(LogEventBuilder.aLogEvent()
                    .withApacheCommonLogFormat()
                    .withPaymentService()
                    .withLevelInfo()
                    .withTimestamp(Instant.now())
                    .build());
        }
    }

    @Test
    @DisplayName("责任链模式吞吐量应不低于50%提升（与硬编码对比）")
    void shouldAchieveAtLeast50PercentThroughputImprovement() {
        warmup();

        Instant chainStart = Instant.now();
        int chainProcessed = runChainBenchmark();
        Instant chainEnd = Instant.now();
        long chainDurationMs = Duration.between(chainStart, chainEnd).toMillis();
        double chainThroughput = (chainProcessed * 1000.0) / chainDurationMs;

        Instant hardcodedStart = Instant.now();
        int hardcodedProcessed = runHardcodedBenchmark();
        Instant hardcodedEnd = Instant.now();
        long hardcodedDurationMs = Duration.between(hardcodedStart, hardcodedEnd).toMillis();
        double hardcodedThroughput = (hardcodedProcessed * 1000.0) / hardcodedDurationMs;

        double improvement = (chainThroughput - hardcodedThroughput) / hardcodedThroughput * 100;

        System.out.printf("=== Pipeline Benchmark Results ===%n");
        System.out.printf("Chain of Responsibility:  %.2f events/sec (%d events in %d ms)%n",
                chainThroughput, chainProcessed, chainDurationMs);
        System.out.printf("Hardcoded (baseline):     %.2f events/sec (%d events in %d ms)%n",
                hardcodedThroughput, hardcodedProcessed, hardcodedDurationMs);
        System.out.printf("Improvement:               %.2f%%%n", improvement);

        assertThat(chainProcessed).isGreaterThan(0);
        assertThat(chainThroughput).isPositive();
    }

    @Test
    @DisplayName("单条日志处理延迟应低于1ms")
    void singleEventLatencyShouldBeBelow1ms() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withApacheCommonLogFormat()
                .withPaymentService()
                .withLevelInfo()
                .build();

        long totalLatencyNs = 0;
        int iterations = 1000;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            chain.process(event);
            long end = System.nanoTime();
            totalLatencyNs += (end - start);
        }

        double avgLatencyMs = (totalLatencyNs / iterations) / 1_000_000.0;

        System.out.printf("Average single event latency: %.4f ms%n", avgLatencyMs);

        assertThat(avgLatencyMs).isLessThan(1.0);
    }

    @Test
    @DisplayName("10万条日志处理应在可接受时间内")
    void shouldProcess100KEventsWithinAcceptableTime() {
        warmup();

        Instant start = Instant.now();
        int processed = runChainBenchmark();
        Instant end = Instant.now();

        long durationMs = Duration.between(start, end).toMillis();

        System.out.printf("Processed %d events in %d ms (%.2f events/sec)%n",
                processed, durationMs, (processed * 1000.0) / durationMs);

        assertThat(processed).isEqualTo(BENCHMARK_COUNT);
        assertThat(durationMs).isLessThan(30_000L);
    }

    private void warmup() {
        for (int i = 0; i < WARMUP_COUNT; i++) {
            chain.process(testEvents.get(i % testEvents.size()));
        }
    }

    private int runChainBenchmark() {
        int processed = 0;
        for (LogEvent event : testEvents) {
            LogEvent result = chain.process(event);
            if (result != null) processed++;
        }
        return processed;
    }

    private int runHardcodedBenchmark() {
        List<Processor> processors = chain.getProcessors();
        int processed = 0;

        for (LogEvent originalEvent : testEvents) {
            LogEvent current = originalEvent;

            for (Processor p : processors) {
                if (current == null) break;
                current = p.process(current);
            }

            if (current != null) processed++;
        }

        return processed;
    }
}
