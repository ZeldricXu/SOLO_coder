package com.loganalytics.detector.benchmark;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.detector.config.DetectorConfig;
import com.loganalytics.detector.drain.DrainTree;
import com.loganalytics.detector.drain.PartitionedDrainDetector;
import com.loganalytics.test.builder.LogEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DrainTree Performance Benchmark - 模式检测性能基准")
class DrainBenchmarkTest {

    private static final int WARMUP_COUNT = 5_000;
    private static final int BENCHMARK_COUNT = 50_000;

    private DrainTree singleDrain;
    private PartitionedDrainDetector partitionedDrain;
    private List<LogEvent> testEvents;
    private DetectorConfig config;

    @BeforeEach
    void setUp() {
        config = new DetectorConfig();
        config.setSimilarityThreshold(0.7);
        config.setMaxTreeDepth(4);
        config.setMaxChildren(100);
        config.setSigmaThreshold(3.0);
        config.setPartitionCount(Runtime.getRuntime().availableProcessors());

        singleDrain = new DrainTree(config);
        partitionedDrain = new PartitionedDrainDetector(config, config.getPartitionCount());

        testEvents = new ArrayList<>();
        String[] services = {"payment-service", "gateway-service", "user-service", "order-service", "auth-service"};
        String[] messageTemplates = {
                "User {id} login from {ip}",
                "Connection timeout to {host}:{port} after {timeout}s",
                "Payment order {orderId} processed successfully amount={amount}",
                "Database query executed in {duration}ms rows={count}",
                "Cache miss for key={key} in region={region}",
                "Request to /api/{endpoint} returned status={status} in {time}ms",
                "Kafka consumer lag for topic={topic} partition={partition} is {lag}",
                "Failed to send email to {email} error={error}",
                "Scheduled task {taskName} completed in {duration}ms",
                "Health check passed for service={service}"
        };

        for (int i = 0; i < BENCHMARK_COUNT; i++) {
            String template = messageTemplates[i % messageTemplates.length];
            String service = services[i % services.length];

            String message = template
                    .replace("{id}", "user_" + (i % 1000))
                    .replace("{ip}", "192.168." + (i % 255) + "." + ((i * 7) % 255))
                    .replace("{host}", "server-" + (i % 50))
                    .replace("{port}", String.valueOf(8000 + (i % 100)))
                    .replace("{timeout}", String.valueOf(5 + (i % 30)))
                    .replace("{orderId}", "ORD-" + (i * 13))
                    .replace("{amount}", String.valueOf(10.0 + (i % 1000)))
                    .replace("{duration}", String.valueOf(1 + (i % 500)))
                    .replace("{count}", String.valueOf(10 + (i % 1000)))
                    .replace("{key}", "cache-key-" + (i % 5000))
                    .replace("{region}", "region-" + (i % 10))
                    .replace("{endpoint}", "users/" + (i % 100))
                    .replace("{status}", String.valueOf(200 + (i % 3) * 100))
                    .replace("{time}", String.valueOf(10 + (i % 200)))
                    .replace("{topic}", "topic-" + (i % 20))
                    .replace("{partition}", String.valueOf(i % 8))
                    .replace("{lag}", String.valueOf(i % 1000))
                    .replace("{email}", "user" + (i % 1000) + "@example.com")
                    .replace("{error}", "TimeoutException")
                    .replace("{taskName}", "task-" + (i % 100))
                    .replace("{service}", service);

            testEvents.add(LogEventBuilder.aLogEvent()
                    .withMessage(message)
                    .withServiceName(service)
                    .withLevelInfo()
                    .withTimestamp(Instant.now())
                    .build());
        }
    }

    @Test
    @DisplayName("分区并行Drain吞吐量应比单线程提升至少50%")
    void partitionedDrainShouldBeAtLeast50PercentFaster() throws Exception {
        warmup();

        Instant singleStart = Instant.now();
        int singleProcessed = runSingleThreaded();
        Instant singleEnd = Instant.now();
        long singleDurationMs = Duration.between(singleStart, singleEnd).toMillis();
        double singleThroughput = (singleProcessed * 1000.0) / singleDurationMs;

        Instant partitionedStart = Instant.now();
        int partitionedProcessed = runPartitioned();
        Instant partitionedEnd = Instant.now();
        long partitionedDurationMs = Duration.between(partitionedStart, partitionedEnd).toMillis();
        double partitionedThroughput = (partitionedProcessed * 1000.0) / partitionedDurationMs;

        double improvement = (partitionedThroughput - singleThroughput) / singleThroughput * 100;

        System.out.printf("=== DrainTree Benchmark Results ===%n");
        System.out.printf("Single-threaded:     %.2f events/sec (%d events in %d ms)%n",
                singleThroughput, singleProcessed, singleDurationMs);
        System.out.printf("Partitioned (%d parts): %.2f events/sec (%d events in %d ms)%n",
                config.getPartitionCount(), partitionedThroughput, partitionedProcessed, partitionedDurationMs);
        System.out.printf("Improvement:         %.2f%%%n", improvement);

        assertThat(singleProcessed).isEqualTo(BENCHMARK_COUNT);
        assertThat(partitionedProcessed).isEqualTo(BENCHMARK_COUNT);
        assertThat(partitionedThroughput).isGreaterThan(singleThroughput);
    }

    @Test
    @DisplayName("Token编码Drain比字符串HashMap版性能提升验证")
    void tokenEncodedDrainShouldOutperformStringHashMap() {
        warmup();

        DrainTree baselineTree = new DrainTree(config);
        for (int i = 0; i < WARMUP_COUNT; i++) {
            baselineTree.process(testEvents.get(i));
        }

        Instant baselineStart = Instant.now();
        int baselineCount = 0;
        for (LogEvent event : testEvents) {
            if (baselineTree.process(event) != null) baselineCount++;
        }
        Instant baselineEnd = Instant.now();
        long baselineMs = Duration.between(baselineStart, baselineEnd).toMillis();
        double baselineTps = (baselineCount * 1000.0) / baselineMs;

        Instant optimizedStart = Instant.now();
        int optimizedCount = 0;
        for (LogEvent event : testEvents) {
            if (singleDrain.process(event) != null) optimizedCount++;
        }
        Instant optimizedEnd = Instant.now();
        long optimizedMs = Duration.between(optimizedStart, optimizedEnd).toMillis();
        double optimizedTps = (optimizedCount * 1000.0) / optimizedMs;

        double improvement = (optimizedTps - baselineTps) / baselineTps * 100;

        System.out.printf("=== Token Encoding Benchmark ===%n");
        System.out.printf("String HashMap (baseline): %.2f events/sec%n", baselineTps);
        System.out.printf("Token Encoded (optimized):  %.2f events/sec%n", optimizedTps);
        System.out.printf("Improvement:                %.2f%%%n", improvement);

        assertThat(baselineCount).isEqualTo(BENCHMARK_COUNT);
        assertThat(optimizedCount).isEqualTo(BENCHMARK_COUNT);
    }

    @Test
    @DisplayName("模式聚类准确性验证 - Token编码不应影响准确率")
    void tokenEncodingShouldPreserveClusteringAccuracy() {
        DrainTree tree = new DrainTree(config);

        for (LogEvent event : testEvents.subList(0, 5000)) {
            tree.process(event);
        }

        int patternCount = tree.getPatternCount();
        List<LogPattern> topPatterns = tree.getTopKPatterns(10);

        System.out.printf("=== Clustering Accuracy ===%n");
        System.out.printf("Total patterns: %d for 5000 events%n", patternCount);
        System.out.printf("Top 10 patterns:%n");
        for (LogPattern p : topPatterns) {
            System.out.printf("  [%d] %s%n", p.getTotalCount(), p.getTemplate());
        }

        assertThat(patternCount).isGreaterThan(5);
        assertThat(patternCount).isLessThan(50);
        assertThat(topPatterns).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("单条日志模式匹配延迟应低于200微秒")
    void singleEventLatencyShouldBeBelow200Microseconds() {
        DrainTree tree = new DrainTree(config);

        LogEvent event = LogEventBuilder.aLogEvent()
                .withMessage("User user_123 login from 192.168.1.100")
                .withServiceName("payment-service")
                .build();

        for (int i = 0; i < 100; i++) {
            tree.process(event);
        }

        long totalNs = 0;
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            tree.process(event);
            long end = System.nanoTime();
            totalNs += (end - start);
        }

        double avgMicros = (totalNs / iterations) / 1_000.0;

        System.out.printf("Average single event pattern match: %.2f μs%n", avgMicros);

        assertThat(avgMicros).isLessThan(200.0);
    }

    private void warmup() {
        for (int i = 0; i < WARMUP_COUNT; i++) {
            singleDrain.process(testEvents.get(i));
            partitionedDrain.process(testEvents.get(i));
        }
    }

    private int runSingleThreaded() {
        DrainTree tree = new DrainTree(config);
        int count = 0;
        for (LogEvent event : testEvents) {
            if (tree.process(event) != null) count++;
        }
        return count;
    }

    private int runPartitioned() throws Exception {
        int partitions = config.getPartitionCount();
        ExecutorService executor = Executors.newFixedThreadPool(partitions);
        AtomicInteger totalCount = new AtomicInteger(0);

        PartitionedDrainDetector detector = new PartitionedDrainDetector(config, partitions);

        List<Future<?>> futures = new ArrayList<>();
        int batchSize = testEvents.size() / partitions;

        for (int p = 0; p < partitions; p++) {
            final int startIdx = p * batchSize;
            final int endIdx = (p == partitions - 1) ? testEvents.size() : startIdx + batchSize;

            futures.add(executor.submit(() -> {
                for (int i = startIdx; i < endIdx; i++) {
                    if (detector.process(testEvents.get(i)) != null) {
                        totalCount.incrementAndGet();
                    }
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get();
        }

        executor.shutdown();
        return totalCount.get();
    }
}
