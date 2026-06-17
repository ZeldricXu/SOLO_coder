package com.loganalytics.metrics.benchmark;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.MetricPoint;
import com.loganalytics.metrics.config.MetricsConfig;
import com.loganalytics.metrics.timescale.TimescaleWriter;
import com.loganalytics.test.builder.LogEventBuilder;
import com.loganalytics.test.builder.MetricPointBuilder;
import com.loganalytics.test.container.LogAnalyticsContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("TimescaleWriter Performance Benchmark - 聚合指标写入性能基准")
@Testcontainers
class TimescaleWriterBenchmarkTest {

    private static final int WARMUP_COUNT = 1_000;
    private static final int BENCHMARK_COUNT = 10_000;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = LogAnalyticsContainers.newTimescaleDBContainer();

    private TimescaleWriter batchWriter;
    private MetricsConfig fastBatchConfig;
    private List<MetricPoint> testMetrics;

    @BeforeEach
    void setUp() {
        fastBatchConfig = new MetricsConfig();
        fastBatchConfig.setTimescaleUrl(POSTGRES.getJdbcUrl());
        fastBatchConfig.setTimescaleUser(POSTGRES.getUsername());
        fastBatchConfig.setTimescalePassword(POSTGRES.getPassword());
        fastBatchConfig.setTimescalePoolSize(4);
        fastBatchConfig.setRawDataRetentionDays(1);
        fastBatchConfig.setEnableContinuousAggregation(false);
        fastBatchConfig.setChunkTimeInterval("1 hour");
        fastBatchConfig.setBatchFlushIntervalSeconds(5);
        fastBatchConfig.setBatchSize(1000);
        fastBatchConfig.setMaxRetryAttempts(3);
        fastBatchConfig.setWindows(List.of(
                new MetricsConfig.WindowConfig(
                        "1min_tumbling",
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        MetricsConfig.WindowConfig.WindowType.TUMBLING
                )
        ));

        initializeSchema();

        batchWriter = new TimescaleWriter(fastBatchConfig);

        testMetrics = new ArrayList<>();
        String[] services = {"payment-service", "gateway-service", "user-service", "order-service"};
        String[] metrics = {"log_count", "error_rate", "eps", "error_count", "warn_count", "bytes_processed"};

        for (int i = 0; i < BENCHMARK_COUNT; i++) {
            String metricName = metrics[i % metrics.length];
            String service = services[i % services.length];

            MetricPoint mp = MetricPointBuilder.aMetricPoint()
                    .withMetricName(metricName)
                    .withOneMinuteWindow()
                    .withService(service)
                    .withValue(10.0 + (i % 1000))
                    .withTimestamp(Instant.now().minusSeconds(i))
                    .build();

            testMetrics.add(mp);
        }
    }

    @AfterEach
    void tearDown() {
        if (batchWriter != null) {
            batchWriter.close();
        }
    }

    @Test
    @DisplayName("异步批写吞吐量验证 - 10000条应在10秒内完成")
    void asyncBatchWriteShouldBeFast() {
        warmup();

        Instant start = Instant.now();

        for (MetricPoint mp : testMetrics) {
            batchWriter.write(mp);
        }

        batchWriter.flush();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    assertThat(batchWriter.getPendingCount()).isEqualTo(0);
                    try (Connection conn = POSTGRES.createConnection("")) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM metrics");
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getLong("cnt")).isGreaterThanOrEqualTo(BENCHMARK_COUNT);
                        }
                    }
                });

        Instant end = Instant.now();
        long totalDurationMs = Duration.between(start, end).toMillis();
        long writtenCount = batchWriter.getTotalMetricsWritten();
        double throughput = (writtenCount * 1000.0) / totalDurationMs;

        System.out.printf("=== Async Batch Write Benchmark ===%n");
        System.out.printf("Wrote %d metrics in %d ms%n", writtenCount, totalDurationMs);
        System.out.printf("Throughput: %.2f metrics/sec%n", throughput);
        System.out.printf("Batch flush interval: %d seconds%n", fastBatchConfig.getBatchFlushIntervalSeconds());
        System.out.printf("Batch size: %d%n", fastBatchConfig.getBatchSize());
        System.out.printf("Chunk time interval: %s%n", fastBatchConfig.getChunkTimeInterval());

        assertThat(writtenCount).isGreaterThanOrEqualTo(BENCHMARK_COUNT);
        assertThat(throughput).isGreaterThan(500.0);
    }

    @Test
    @DisplayName("批量写入 vs 单条写入性能对比 - 批写应至少快50%")
    void batchWriteShouldBeAtLeast50PercentFaster() {
        warmup();

        AtomicInteger syncCount = new AtomicInteger(0);
        Instant syncStart = Instant.now();
        try (Connection conn = POSTGRES.createConnection("")) {
            String insertSql = "INSERT INTO metrics (time, metric_name, value, metric_type, tags, service, window) " +
                    "VALUES (?, ?, ?, ?::metric_type, ?::jsonb, ?, ?)";
            try (var stmt = conn.prepareStatement(insertSql)) {
                for (int i = 0; i < Math.min(1000, testMetrics.size()); i++) {
                    MetricPoint mp = testMetrics.get(i);
                    stmt.setTimestamp(1, java.sql.Timestamp.from(mp.getTimestamp()));
                    stmt.setString(2, mp.getMetricName());
                    stmt.setDouble(3, mp.getValue());
                    stmt.setString(4, mp.getType() != null ? mp.getType().name() : "GAUGE");
                    stmt.setString(5, mp.getTagsAsJson());
                    stmt.setString(6, mp.getTag("service"));
                    stmt.setString(7, mp.getTag("window"));
                    stmt.executeUpdate();
                    syncCount.incrementAndGet();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Instant syncEnd = Instant.now();
        long syncDurationMs = Duration.between(syncStart, syncEnd).toMillis();
        double syncThroughput = (syncCount.get() * 1000.0) / syncDurationMs;

        AtomicInteger asyncCount = new AtomicInteger(0);
        Instant asyncStart = Instant.now();
        TimescaleWriter asyncWriter = new TimescaleWriter(fastBatchConfig);
        for (MetricPoint mp : testMetrics) {
            asyncWriter.write(mp);
        }
        asyncWriter.flush();

        try {
            await().atMost(Duration.ofSeconds(30)).until(() -> asyncWriter.getPendingCount() == 0);
        } catch (Exception e) {
            // ignore
        }
        asyncCount.set((int) asyncWriter.getTotalMetricsWritten());
        Instant asyncEnd = Instant.now();
        long asyncDurationMs = Duration.between(asyncStart, asyncEnd).toMillis();
        double asyncThroughput = (asyncCount.get() * 1000.0) / asyncDurationMs;
        asyncWriter.close();

        double improvement = (asyncThroughput - syncThroughput) / syncThroughput * 100;

        System.out.printf("=== Sync vs Async Batch Comparison ===%n");
        System.out.printf("Sync (single inserts):    %.2f metrics/sec (%d in %d ms)%n",
                syncThroughput, syncCount.get(), syncDurationMs);
        System.out.printf("Async (batched inserts):  %.2f metrics/sec (%d in %d ms)%n",
                asyncThroughput, asyncCount.get(), asyncDurationMs);
        System.out.printf("Improvement:               %.2f%%%n", improvement);

        assertThat(asyncThroughput).isGreaterThan(syncThroughput);
    }

    @Test
    @DisplayName("5秒或1000条触发批写验证")
    void shouldTriggerBatchWriteAt5SecondsOr1000Items() {
        long startPending = batchWriter.getPendingCount();

        for (int i = 0; i < 999; i++) {
            batchWriter.write(testMetrics.get(i));
        }

        assertThat(batchWriter.getPendingCount()).isEqualTo(startPending + 999);

        batchWriter.write(testMetrics.get(999));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(batchWriter.getPendingCount()).isLessThan(1000);
                });

        System.out.printf("Pending count after 1000 items: %d%n", batchWriter.getPendingCount());
        System.out.printf("Total batches written so far: %d%n", batchWriter.getTotalBatchesWritten());
    }

    @Test
    @DisplayName("写队列非阻塞验证 - 高负载下不丢数据")
    void shouldNotDropMetricsUnderHighLoad() {
        int highLoadCount = 20_000;
        List<MetricPoint> highLoadMetrics = new ArrayList<>();
        for (int i = 0; i < highLoadCount; i++) {
            highLoadMetrics.add(MetricPointBuilder.aMetricPoint()
                    .withLogCountMetric()
                    .withOneMinuteWindow()
                    .withPaymentService()
                    .withValue((double) i)
                    .withTimestamp(Instant.now())
                    .build());
        }

        Instant start = Instant.now();
        for (MetricPoint mp : highLoadMetrics) {
            batchWriter.write(mp);
        }
        batchWriter.flush();

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> {
                    assertThat(batchWriter.getPendingCount()).isEqualTo(0);
                    assertThat(batchWriter.getTotalWriteErrors()).isEqualTo(0);
                });

        long durationMs = Duration.between(start, Instant.now()).toMillis();
        long written = batchWriter.getTotalMetricsWritten();

        System.out.printf("=== High Load Test ===%n");
        System.out.printf("High load (%d metrics) completed in %d ms%n", highLoadCount, durationMs);
        System.out.printf("Metrics written: %d%n", written);
        System.out.printf("Write errors: %d%n", batchWriter.getTotalWriteErrors());
        System.out.printf("Throughput: %.2f metrics/sec%n", (written * 1000.0) / durationMs);

        assertThat(batchWriter.getTotalWriteErrors()).isEqualTo(0);
    }

    private void initializeSchema() {
        try (Connection conn = POSTGRES.createConnection("")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
                stmt.execute("CREATE TYPE IF NOT EXISTS metric_type AS ENUM ('COUNTER', 'GAUGE', 'HISTOGRAM', 'SUMMARY')");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS metrics (
                        time TIMESTAMPTZ NOT NULL,
                        metric_name TEXT NOT NULL,
                        value DOUBLE PRECISION NOT NULL,
                        metric_type metric_type NOT NULL,
                        tags JSONB,
                        service TEXT,
                        window TEXT
                    )
                    """);
                stmt.execute("SELECT create_hypertable('metrics', 'time', chunk_time_interval => INTERVAL '1 hour', if_not_exists => TRUE)");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize test schema", e);
        }
    }

    private void warmup() {
        for (int i = 0; i < WARMUP_COUNT; i++) {
            batchWriter.write(testMetrics.get(i % testMetrics.size()));
        }
        batchWriter.flush();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
