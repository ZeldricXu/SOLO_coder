package com.loganalytics.test.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.MetricPoint;
import com.loganalytics.common.serde.LogEventSerde;
import com.loganalytics.common.util.JsonUtils;
import com.loganalytics.detector.anomaly.FrequencyAnomalyDetector;
import com.loganalytics.detector.baseline.BaselineManager;
import com.loganalytics.detector.config.DetectorConfig;
import com.loganalytics.detector.drain.DrainTree;
import com.loganalytics.metrics.config.MetricsConfig;
import com.loganalytics.metrics.timescale.TimescaleWriter;
import com.loganalytics.metrics.window.AggregateValue;
import com.loganalytics.metrics.window.AggregationKey;
import com.loganalytics.metrics.window.WindowedAggregator;
import com.loganalytics.pipeline.cmdb.CmdbService;
import com.loganalytics.pipeline.config.PipelineConfig;
import com.loganalytics.pipeline.enrich.LogEnricher;
import com.loganalytics.pipeline.filter.LogFilter;
import com.loganalytics.pipeline.parse.LogParser;
import com.loganalytics.pipeline.route.LogRouter;
import com.loganalytics.test.builder.LogEventBuilder;
import com.loganalytics.test.builder.MetricPointBuilder;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("全链路集成测试")
class FullPipelineIntegrationTest extends AbstractIntegrationTest {

    private static final String INPUT_TOPIC = "raw-logs";
    private static final String PARSED_TOPIC = "parsed-logs";
    private static final String ENRICHED_TOPIC = "enriched-logs";
    private static final String METRICS_TOPIC = "metrics";
    private static final String DEAD_LETTER_TOPIC = "dlq-logs";

    private final ObjectMapper objectMapper = JsonUtils.getMapper();
    private final LogEventSerde logEventSerde = new LogEventSerde();

    private LogParser logParser;
    private LogFilter logFilter;
    private LogEnricher logEnricher;
    private LogRouter logRouter;
    private DrainTree drainTree;
    private BaselineManager baselineManager;
    private FrequencyAnomalyDetector frequencyAnomalyDetector;
    private WindowedAggregator windowedAggregator;
    private TimescaleWriter timescaleWriter;

    @BeforeEach
    void setUp() throws Exception {
        createTopics(INPUT_TOPIC, PARSED_TOPIC, ENRICHED_TOPIC, METRICS_TOPIC, DEAD_LETTER_TOPIC);
        initializePipelineComponents();
    }

    @Test
    @DisplayName("全链路：Agent采集 -> Kafka -> 管道处理 -> 模式检测 -> 指标聚合 -> 数据库写入")
    void shouldProcessLogsEndToEndThroughEntirePipeline() throws Exception {
        LogEvent logEvent = LogEventBuilder.aLogEvent()
                .withApacheCommonLogFormat()
                .withPaymentService()
                .withLevelInfo()
                .build();

        producer.send(new ProducerRecord<>(
                INPUT_TOPIC,
                logEvent.getService(),
                objectMapper.writeValueAsString(logEvent)
        )).get(5, TimeUnit.SECONDS);

        ConsumerRecords<String, String> records = consumeFromTopic(INPUT_TOPIC, 1);
        assertThat(records).hasSize(1);
        LogEvent receivedEvent = objectMapper.readValue(
                records.iterator().next().value(), LogEvent.class
        );
        assertThat(receivedEvent.getMessage()).isEqualTo(logEvent.getMessage());

        LogEvent parsedEvent = logParser.parse(receivedEvent);
        assertThat(parsedEvent.getField("method")).isNotNull();
        assertThat(parsedEvent.getField("status_code")).isNotNull();
        assertThat(parsedEvent.isParsed()).isTrue();

        boolean shouldPass = logFilter.accept(parsedEvent);
        assertThat(shouldPass).isTrue();

        LogEvent enrichedEvent = logEnricher.enrich(parsedEvent);
        assertThat(enrichedEvent.getTag("env")).isEqualTo("production");
        assertThat(enrichedEvent.getTag("region")).isEqualTo("us-east-1");

        String routingTopic = logRouter.route(enrichedEvent);
        assertThat(routingTopic).isEqualTo(PARSED_TOPIC);

        String patternId = drainTree.addLogMessage(enrichedEvent.getMessage());
        assertThat(patternId).isNotNull();
        enrichedEvent.setPatternId(patternId);
        enrichedEvent.setPatternTemplate(drainTree.getPattern(patternId).getTemplate());

        baselineManager.recordPattern(patternId, enrichedEvent.getService());

        List<MetricPoint> metrics = windowedAggregator.processLogEvent(enrichedEvent);
        assertThat(metrics).isNotEmpty();

        for (MetricPoint metric : metrics) {
            timescaleWriter.write(metric);
        }

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    try (Connection conn = getPostgresConnection()) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT COUNT(*) as cnt FROM metrics WHERE service = 'payment-service'"
                            );
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getLong("cnt")).isGreaterThanOrEqualTo(1);
                        }
                    }
                });

        try (Connection conn = getPostgresConnection()) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT metric_name, value FROM metrics WHERE service = 'payment-service' ORDER BY time DESC LIMIT 5"
                );
                boolean foundLogCount = false;
                while (rs.next()) {
                    if ("log_count".equals(rs.getString("metric_name"))) {
                        assertThat(rs.getDouble("value")).isGreaterThan(0);
                        foundLogCount = true;
                    }
                }
                assertThat(foundLogCount).isTrue();
            }
        }
    }

    @Test
    @DisplayName("通过API查询最新指标")
    void shouldQueryLatestMetricsViaApiSimulation() throws Exception {
        MetricPoint metric1 = MetricPointBuilder.aMetricPoint()
                .withLogCountMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(150.0)
                .withTimestamp(Instant.now())
                .build();

        MetricPoint metric2 = MetricPointBuilder.aMetricPoint()
                .withErrorRateMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(0.02)
                .withTimestamp(Instant.now())
                .build();

        MetricPoint metric3 = MetricPointBuilder.aMetricPoint()
                .withEpsMetric()
                .withOneMinuteWindow()
                .withPaymentService()
                .withValue(25.0)
                .withTimestamp(Instant.now())
                .build();

        timescaleWriter.write(metric1);
        timescaleWriter.write(metric2);
        timescaleWriter.write(metric3);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    try (Connection conn = getPostgresConnection()) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT COUNT(DISTINCT metric_name) as cnt FROM metrics WHERE service = 'payment-service'"
                            );
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getLong("cnt")).isGreaterThanOrEqualTo(3);
                        }
                    }
                });

        try (Connection conn = getPostgresConnection()) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT metric_name, value, time " +
                                "FROM metrics WHERE service = 'payment-service' " +
                                "ORDER BY time DESC LIMIT 10"
                );

                boolean hasLogCount = false;
                boolean hasErrorRate = false;
                boolean hasEps = false;

                while (rs.next()) {
                    String metricName = rs.getString("metric_name");
                    double value = rs.getDouble("value");

                    switch (metricName) {
                        case "log_count":
                            assertThat(value).isEqualTo(150.0);
                            hasLogCount = true;
                            break;
                        case "error_rate":
                            assertThat(value).isEqualTo(0.02);
                            hasErrorRate = true;
                            break;
                        case "eps":
                            assertThat(value).isEqualTo(25.0);
                            hasEps = true;
                            break;
                    }
                }

                assertThat(hasLogCount).isTrue();
                assertThat(hasErrorRate).isTrue();
                assertThat(hasEps).isTrue();
            }
        }
    }

    @Test
    @DisplayName("多服务日志并行处理")
    void shouldHandleMultipleServicesInParallel() throws Exception {
        for (int i = 0; i < 50; i++) {
            LogEvent paymentEvent = LogEventBuilder.aLogEvent()
                    .withUserLoginMessage("user-" + i, "192.168.1." + (i % 255))
                    .withPaymentService()
                    .withLevelInfo()
                    .build();

            LogEvent gatewayEvent = LogEventBuilder.aLogEvent()
                    .withConnectionTimeoutMessage("api.example.com", 443, 30)
                    .withGatewayService()
                    .withLevelError()
                    .build();

            producer.send(new ProducerRecord<>(
                    INPUT_TOPIC,
                    paymentEvent.getService(),
                    objectMapper.writeValueAsString(paymentEvent)
            ));

            producer.send(new ProducerRecord<>(
                    INPUT_TOPIC,
                    gatewayEvent.getService(),
                    objectMapper.writeValueAsString(gatewayEvent)
            ));
        }

        producer.flush();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    try (Connection conn = getPostgresConnection()) {
                        try (Statement stmt = conn.createStatement()) {
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT service, SUM(value) as total " +
                                            "FROM metrics WHERE metric_name = 'log_count' " +
                                            "GROUP BY service ORDER BY service"
                            );
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("service")).isEqualTo("gateway-service");
                            assertThat(rs.getDouble("total")).isGreaterThanOrEqualTo(50.0);
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("service")).isEqualTo("payment-service");
                            assertThat(rs.getDouble("total")).isGreaterThanOrEqualTo(50.0);
                        }
                    }
                });
    }

    private void createTopics(String... topics) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                "bootstrap.servers", getKafkaBootstrapServers()
        ))) {
            Set<String> existingTopics = admin.listTopics().names().get();
            for (String topic : topics) {
                if (!existingTopics.contains(topic)) {
                    admin.createTopics(List.of(
                            new NewTopic(topic, 6, (short) 1)
                    )).all().get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private ConsumerRecords<String, String> consumeFromTopic(String topic, int expectedCount) {
        consumer.subscribe(List.of(topic));
        ConsumerRecords<String, String> allRecords = null;

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    assertThat(records.count()).isGreaterThanOrEqualTo(expectedCount);
                });

        return consumer.poll(Duration.ofMillis(1000));
    }

    private void initializePipelineComponents() {
        logParser = new LogParser();
        logParser.initialize(Map.of(
                "grok.pattern.common_log", "%{COMMONAPACHELOG}",
                "grok.pattern.combined_log", "%{COMBINEDAPACHELOG}"
        ));

        logFilter = new LogFilter();
        logFilter.configure(new PipelineConfig.FilterConfig(
                Set.of("DEBUG"),
                Set.of(),
                Set.of(),
                true
        ));

        CmdbService cmdbService = new CmdbService(
                "http://localhost:8080",
                1000,
                5000
        );
        logEnricher = new LogEnricher(cmdbService);
        logEnricher.initialize(Map.of(
                "enrich.cmdb.timeout", "5000",
                "enrich.cache.expire.seconds", "300"
        ));

        logRouter = new LogRouter(Map.of(
                "INFO", PARSED_TOPIC,
                "WARN", PARSED_TOPIC,
                "ERROR", "error-logs"
        ), DEAD_LETTER_TOPIC);

        drainTree = new DrainTree(
                4, 100, 0.5,
                Set.of("payment-service", "gateway-service")
        );

        DetectorConfig detectorConfig = new DetectorConfig();
        detectorConfig.setBaselineWindowMinutes(1);
        detectorConfig.setBaselineWarmupMinutes(1);
        detectorConfig.setFrequencySigmaThreshold(3.0);
        detectorConfig.setAnomalyCooldownSeconds(10);
        baselineManager = new BaselineManager(detectorConfig);

        frequencyAnomalyDetector = new FrequencyAnomalyDetector(
                detectorConfig, baselineManager
        );

        MetricsConfig metricsConfig = new MetricsConfig();
        metricsConfig.setTimescaleUrl(getPostgresJdbcUrl());
        metricsConfig.setTimescaleUser(getPostgresUsername());
        metricsConfig.setTimescalePassword(getPostgresPassword());
        metricsConfig.setTimescalePoolSize(2);
        metricsConfig.setRawDataRetentionDays(7);
        metricsConfig.setEnableContinuousAggregation(false);
        metricsConfig.setWindows(List.of(
                new MetricsConfig.WindowConfig(
                        "1min_tumbling",
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        MetricsConfig.WindowConfig.WindowType.TUMBLING
                )
        ));

        windowedAggregator = new WindowedAggregator(metricsConfig);
        timescaleWriter = new TimescaleWriter(metricsConfig);
    }
}
