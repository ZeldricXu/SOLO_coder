package com.loganalytics.metrics.timescale;

import com.loganalytics.common.model.MetricPoint;
import com.loganalytics.metrics.config.MetricsConfig;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("TimescaleWriter - 时序数据库写入")
@Testcontainers
class TimescaleWriterTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = LogAnalyticsContainers.newTimescaleDBContainer();

    private TimescaleWriter writer;
    private MetricsConfig config;

    @BeforeEach
    void setUp() {
        config = new MetricsConfig();
        config.setTimescaleUrl(POSTGRES.getJdbcUrl());
        config.setTimescaleUser(POSTGRES.getUsername());
        config.setTimescalePassword(POSTGRES.getPassword());
        config.setTimescalePoolSize(2);
        config.setRawDataRetentionDays(7);
        config.setEnableContinuousAggregation(false);
        config.setWindows(List.of(
                new MetricsConfig.WindowConfig(
                        "1min_tumbling",
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        MetricsConfig.WindowConfig.WindowType.TUMBLING
                )
        ));

        try (Connection conn = POSTGRES.createConnection("")) {
            try (Statement stmt = conn.createStatement()) {
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
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize test schema", e);
        }

        writer = new TimescaleWriter(config);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.close();
        }
    }

    @Test
    @DisplayName("窗口关闭后聚合结果正确写入数据库")
    void shouldWriteAggregatedResultsToDatabaseAfterWindowClose() throws Exception {
        MetricPoint metric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .withTimestamp(Instant.now())
                .build();

        writer.write(metric);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    try (Connection conn = POSTGRES.createConnection("")) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT COUNT(*) as cnt FROM metrics WHERE metric_name = 'log_count'"
                            );
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getLong("cnt")).isGreaterThanOrEqualTo(1);
                        }
                    }
                });

        try (Connection conn = POSTGRES.createConnection("")) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT * FROM metrics WHERE metric_name = 'log_count' ORDER BY time DESC LIMIT 1"
                );
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("value")).isEqualTo(100.0);
                assertThat(rs.getString("service")).isEqualTo("payment-service");
                assertThat(rs.getString("window")).isEqualTo("1min_tumbling");
                assertThat(rs.getString("metric_type")).isEqualTo("COUNTER");
            }
        }
    }

    @Test
    @DisplayName("批量写入多个指标")
    void shouldWriteMultipleMetricsInBatch() throws Exception {
        for (int i = 0; i < 10; i++) {
            MetricPoint metric = MetricPointBuilder.aMetricPoint()
                    .withLogCountMetric()
                    .withOneMinuteWindow()
                    .withPaymentService()
                    .withValue((double) (i * 10))
                    .withTimestamp(Instant.now().minusSeconds(i * 60))
                    .build();
            writer.write(metric);
        }

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    try (Connection conn = POSTGRES.createConnection("")) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT COUNT(*) as cnt FROM metrics WHERE metric_name = 'log_count'"
                            );
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getLong("cnt")).isGreaterThanOrEqualTo(10);
                        }
                    }
                });
    }

    @Test
    @DisplayName("writeAll正确写入所有指标")
    void shouldWriteAllMetricsFromList() throws Exception {
        List<MetricPoint> metrics = List.of(
                MetricPointBuilder.aMetricPoint()
                        .withLogCountMetric()
                        .withOneMinuteWindow()
                        .withPaymentService()
                        .withValue(100.0)
                        .build(),
                MetricPointBuilder.aMetricPoint()
                        .withErrorRateMetric()
                        .withOneMinuteWindow()
                        .withPaymentService()
                        .withValue(0.05)
                        .build(),
                MetricPointBuilder.aMetricPoint()
                        .withEpsMetric()
                        .withOneMinuteWindow()
                        .withPaymentService()
                        .withValue(50.0)
                        .build()
        );

        writer.writeAll(metrics);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    try (Connection conn = POSTGRES.createConnection("")) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT COUNT(DISTINCT metric_name) as cnt FROM metrics"
                            );
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getLong("cnt")).isGreaterThanOrEqualTo(3);
                        }
                    }
                });
    }

    @Test
    @DisplayName("不同服务的指标正确写入")
    void shouldWriteMetricsForDifferentServices() throws Exception {
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

        writer.write(paymentMetric);
        writer.write(gatewayMetric);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    try (Connection conn = POSTGRES.createConnection("")) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT service, SUM(value) as total " +
                                            "FROM metrics WHERE metric_name = 'log_count' " +
                                            "GROUP BY service ORDER BY service"
                            );
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("service")).isEqualTo("gateway-service");
                            assertThat(rs.getDouble("total")).isEqualTo(200.0);
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("service")).isEqualTo("payment-service");
                            assertThat(rs.getDouble("total")).isEqualTo(100.0);
                        }
                    }
                });
    }

    @Test
    @DisplayName("标签正确序列化为JSON")
    void shouldSerializeTagsAsJson() throws Exception {
        MetricPoint metric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .withLevelError()
                .withErrorCode("E500")
                .withPatternId("pattern-123")
                .build();

        writer.write(metric);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    try (Connection conn = POSTGRES.createConnection("")) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT tags FROM metrics WHERE metric_name = 'log_count' LIMIT 1"
                            );
                            assertThat(rs.next()).isTrue();
                            String tagsJson = rs.getString("tags");
                            assertThat(tagsJson).contains("\"service\":\"payment-service\"");
                            assertThat(tagsJson).contains("\"level\":\"ERROR\"");
                            assertThat(tagsJson).contains("\"error_code\":\"E500\"");
                            assertThat(tagsJson).contains("\"pattern_id\":\"pattern-123\"");
                        }
                    }
                });
    }

    @Test
    @DisplayName("获取诊断信息")
    void shouldReturnDiagnostics() {
        Map<String, Object> diagnostics = writer.getDiagnostics();

        assertThat(diagnostics).containsKey("queueSize");
        assertThat(diagnostics).containsKey("queueCapacity");
        assertThat(diagnostics).containsKey("poolSize");
        assertThat(diagnostics).containsKey("activeConnections");
        assertThat(diagnostics).containsKey("idleConnections");

        assertThat((Integer) diagnostics.get("queueSize")).isEqualTo(0);
        assertThat((Integer) diagnostics.get("queueCapacity")).isGreaterThan(0);
    }

    @Test
    @DisplayName("关闭后不再接受新的写入")
    void shouldNotAcceptWritesAfterClose() {
        writer.close();

        MetricPoint metric = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .build();

        writer.write(metric);

        Map<String, Object> diagnostics = writer.getDiagnostics();
        assertThat((Integer) diagnostics.get("queueSize")).isEqualTo(0);
    }

    @Test
    @DisplayName("null时间戳使用当前时间")
    void shouldHandleNullMetricTypeGracefully() throws Exception {
        MetricPoint metric = new MetricPoint(
                "test-id", "test_metric", Instant.now(), 42.0, null
        );
        metric.addTag("service", "test-service");
        metric.addTag("window", "1min_tumbling");

        writer.write(metric);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    try (Connection conn = POSTGRES.createConnection("")) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT COUNT(*) as cnt FROM metrics WHERE metric_name = 'test_metric'"
                            );
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getLong("cnt")).isGreaterThanOrEqualTo(1);
                        }
                    }
                });
    }

    @Test
    @DisplayName("正确处理不同的指标类型")
    void shouldHandleDifferentMetricTypes() throws Exception {
        MetricPoint counter = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withType(MetricPoint.MetricType.COUNTER)
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(100.0)
                .build();

        MetricPoint gauge = MetricPointBuilder.aMetricPoint()
                .withErrorRateMetric()
                .withType(MetricPoint.MetricType.GAUGE)
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(0.05)
                .build();

        writer.write(counter);
        writer.write(gauge);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    try (Connection conn = POSTGRES.createConnection("")) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT metric_type, COUNT(*) as cnt " +
                                            "FROM metrics GROUP BY metric_type ORDER BY metric_type"
                            );
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("metric_type")).isEqualTo("COUNTER");
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("metric_type")).isEqualTo("GAUGE");
                        }
                    }
                });
    }
}
