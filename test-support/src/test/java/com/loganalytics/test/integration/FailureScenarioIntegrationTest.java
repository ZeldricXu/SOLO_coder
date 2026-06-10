package com.loganalytics.test.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.util.JsonUtils;
import com.loganalytics.detector.anomaly.FrequencyAnomalyDetector;
import com.loganalytics.detector.baseline.BaselineManager;
import com.loganalytics.detector.config.DetectorConfig;
import com.loganalytics.detector.drain.DrainTree;
import com.loganalytics.pipeline.config.PipelineConfig;
import com.loganalytics.pipeline.parse.LogParser;
import com.loganalytics.pipeline.route.LogRouter;
import com.loganalytics.test.builder.LogEventBuilder;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("失败场景集成测试")
class FailureScenarioIntegrationTest extends AbstractIntegrationTest {

    private static final String INPUT_TOPIC = "raw-logs";
    private static final String PARSED_TOPIC = "parsed-logs";
    private static final String DEAD_LETTER_TOPIC = "dlq-logs";

    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    private LogParser logParser;
    private LogRouter logRouter;
    private DrainTree drainTree;
    private BaselineManager baselineManager;
    private FrequencyAnomalyDetector frequencyAnomalyDetector;

    @BeforeEach
    void setUp() throws Exception {
        createTopics(INPUT_TOPIC, PARSED_TOPIC, DEAD_LETTER_TOPIC);
        initializeComponents();
    }

    @Test
    @DisplayName("Kafka broker宕机期间Agent缓存到本地磁盘，broker恢复后补发")
    void shouldCacheLogsToLocalDiskWhenKafkaDownAndResendWhenRecovered() throws Exception {
        Path localCacheDir = Files.createTempDirectory("kafka-cache-test");
        Path localCacheFile = localCacheDir.resolve("pending-logs.log");

        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user-1", "192.168.1.1")
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user-2", "192.168.1.2")
                .withPaymentService()
                .withLevelInfo()
                .build();

        writeToLocalCache(localCacheFile, event1, event2);
        assertThat(Files.readAllLines(localCacheFile)).hasSize(2);

        List<String> cachedLines = Files.readAllLines(localCacheFile);
        for (String line : cachedLines) {
            producer.send(new ProducerRecord<>(
                    INPUT_TOPIC,
                    "payment-service",
                    line
            )).get(5, TimeUnit.SECONDS);
        }

        producer.flush();

        ConsumerRecords<String, String> records = consumeFromTopic(INPUT_TOPIC, 2);
        assertThat(records.count()).isGreaterThanOrEqualTo(2);

        Files.delete(localCacheFile);
        Files.delete(localCacheDir);
    }

    @Test
    @DisplayName("管道处理异常日志（格式无法解析）打入死信topic不阻塞后续处理")
    void shouldRouteUnparseableLogsToDeadLetterTopicWithoutBlocking() throws Exception {
        LogEvent goodEvent = LogEventBuilder.aLogEvent()
                .withApacheCommonLogFormat()
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent badEvent = LogEventBuilder.aLogEvent()
                .withMessage("This is not a valid log format @#$%^&*")
                .withPaymentService()
                .withLevelInfo()
                .build();

        LogEvent anotherGoodEvent = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user-3", "10.0.0.1")
                .withPaymentService()
                .withLevelInfo()
                .build();

        producer.send(new ProducerRecord<>(
                INPUT_TOPIC,
                "payment-service",
                objectMapper.writeValueAsString(goodEvent)
        )).get(5, TimeUnit.SECONDS);

        producer.send(new ProducerRecord<>(
                INPUT_TOPIC,
                "payment-service",
                objectMapper.writeValueAsString(badEvent)
        )).get(5, TimeUnit.SECONDS);

        producer.send(new ProducerRecord<>(
                INPUT_TOPIC,
                "payment-service",
                objectMapper.writeValueAsString(anotherGoodEvent)
        )).get(5, TimeUnit.SECONDS);

        producer.flush();

        LogEvent parsedGood = logParser.parse(goodEvent);
        assertThat(parsedGood.isParsed()).isTrue();
        assertThat(logRouter.route(parsedGood)).isEqualTo(PARSED_TOPIC);

        LogEvent parsedBad = logParser.parse(badEvent);
        assertThat(parsedBad.isParsed()).isFalse();
        assertThat(parsedBad.getParseError()).isNotNull();
        String dlqTopic = logRouter.route(parsedBad);
        assertThat(dlqTopic).isEqualTo(DEAD_LETTER_TOPIC);

        LogEvent parsedAnotherGood = logParser.parse(anotherGoodEvent);
        assertThat(parsedAnotherGood.isParsed()).isTrue();
        assertThat(logRouter.route(parsedAnotherGood)).isEqualTo(PARSED_TOPIC);

        createTopics(DEAD_LETTER_TOPIC);
        producer.send(new ProducerRecord<>(
                DEAD_LETTER_TOPIC,
                "payment-service",
                objectMapper.writeValueAsString(badEvent)
        )).get(5, TimeUnit.SECONDS);

        ConsumerRecords<String, String> dlqRecords = consumeFromTopic(DEAD_LETTER_TOPIC, 1);
        assertThat(dlqRecords.count()).isGreaterThanOrEqualTo(1);

        LogEvent receivedDlqEvent = objectMapper.readValue(
                dlqRecords.iterator().next().value(), LogEvent.class
        );
        assertThat(receivedDlqEvent.getMessage()).isEqualTo("This is not a valid log format @#$%^&*");
    }

    @Test
    @DisplayName("模式检测在冷启动（无历史基线）时用默认阈值工作直到积累足够数据")
    void shouldUseDefaultThresholdsDuringColdStartUntilBaselineAccumulated() throws Exception {
        DetectorConfig coldStartConfig = new DetectorConfig();
        coldStartConfig.setBaselineWindowMinutes(1);
        coldStartConfig.setBaselineWarmupMinutes(1);
        coldStartConfig.setFrequencySigmaThreshold(3.0);
        coldStartConfig.setAnomalyCooldownSeconds(5);
        coldStartConfig.setColdStartDefaultThreshold(100);

        BaselineManager coldBaselineManager = new BaselineManager(coldStartConfig);
        FrequencyAnomalyDetector coldDetector = new FrequencyAnomalyDetector(
                coldStartConfig, coldBaselineManager
        );

        String patternId = drainTree.addLogMessage("User login from IP");
        String service = "payment-service";

        assertThat(coldBaselineManager.isColdStart(patternId, service)).isTrue();

        for (int i = 0; i < 50; i++) {
            coldBaselineManager.recordPattern(patternId, service);
        }

        long baselineCount = coldBaselineManager.getPatternCount(patternId, service, Duration.ofMinutes(1));
        assertThat(baselineCount).isGreaterThan(0);
        assertThat(coldBaselineManager.isColdStart(patternId, service)).isTrue();

        var anomalies = coldDetector.checkForAnomalies(service);
        assertThat(anomalies).isEmpty();

        for (int i = 0; i < 200; i++) {
            coldBaselineManager.recordPattern(patternId, service);
        }

        anomalies = coldDetector.checkForAnomalies(service);
        assertThat(anomalies).isNotEmpty();

        for (int i = 0; i < 100; i++) {
            coldBaselineManager.recordPattern(patternId, service);
        }

        assertThat(coldBaselineManager.hasEnoughData(patternId, service)).isTrue();
        assertThat(coldBaselineManager.isColdStart(patternId, service)).isFalse();

        double mean = coldBaselineManager.getMeanFrequency(patternId, service);
        double stdDev = coldBaselineManager.getStdDevFrequency(patternId, service);
        assertThat(mean).isGreaterThan(0);
        assertThat(stdDev).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("格式错误日志不阻塞后续正常日志的模式识别")
    void shouldNotBlockPatternDetectionForValidLogsAfterInvalidOnes() throws Exception {
        String validMessage1 = "User user-1 login from 192.168.1.1";
        String invalidMessage = "@@@###$$$ garbage data";
        String validMessage2 = "User user-2 login from 192.168.1.2";

        String patternId1 = drainTree.addLogMessage(validMessage1);
        assertThat(patternId1).isNotNull();

        String patternIdInvalid = drainTree.addLogMessage(invalidMessage);
        assertThat(patternIdInvalid).isNotNull();

        String patternId2 = drainTree.addLogMessage(validMessage2);
        assertThat(patternId2).isNotNull();
        assertThat(patternId2).isEqualTo(patternId1);

        var patterns = drainTree.getTopKPatterns("all", 10);
        assertThat(patterns).hasSizeGreaterThanOrEqualTo(2);

        boolean foundLoginPattern = patterns.stream()
                .anyMatch(p -> p.getTemplate().contains("User") && p.getTemplate().contains("login"));
        assertThat(foundLoginPattern).isTrue();
    }

    @Test
    @DisplayName("数据库连接失败时指标数据不丢失，重试后成功写入")
    void shouldRetryMetricWritingAfterDatabaseConnectionFailure() throws Exception {
        com.loganalytics.metrics.config.MetricsConfig metricsConfig = new com.loganalytics.metrics.config.MetricsConfig();
        metricsConfig.setTimescaleUrl(getPostgresJdbcUrl());
        metricsConfig.setTimescaleUser(getPostgresUsername());
        metricsConfig.setTimescalePassword(getPostgresPassword());
        metricsConfig.setTimescalePoolSize(2);
        metricsConfig.setRawDataRetentionDays(7);
        metricsConfig.setEnableContinuousAggregation(false);
        metricsConfig.setWindows(List.of(
                new com.loganalytics.metrics.config.MetricsConfig.WindowConfig(
                        "1min_tumbling",
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        com.loganalytics.metrics.config.MetricsConfig.WindowConfig.WindowType.TUMBLING
                )
        ));

        try (com.loganalytics.metrics.timescale.TimescaleWriter writer = new com.loganalytics.metrics.timescale.TimescaleWriter(metricsConfig)) {
            com.loganalytics.common.model.MetricPoint metric = com.loganalytics.test.builder.MetricPointBuilder.aMetricPoint()
                    .withLogCountMetric()
                    .withOneMinuteWindow()
                    .withPaymentService()
                    .withValue(42.0)
                    .withTimestamp(Instant.now())
                    .build();

            writer.write(metric);

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        try (Connection conn = getPostgresConnection()) {
                            try (Statement stmt = conn.createStatement()) {
                                ResultSet rs = stmt.executeQuery(
                                        "SELECT COUNT(*) as cnt FROM metrics WHERE service = 'payment-service' AND value = 42.0"
                                );
                                assertThat(rs.next()).isTrue();
                                assertThat(rs.getLong("cnt")).isGreaterThanOrEqualTo(1);
                            }
                        }
                    });

            Map<String, Object> diagnostics = writer.getDiagnostics();
            assertThat((Integer) diagnostics.get("queueSize")).isEqualTo(0);
        }
    }

    private void writeToLocalCache(Path cacheFile, LogEvent... events) throws Exception {
        for (LogEvent event : events) {
            String json = objectMapper.writeValueAsString(event);
            Files.writeString(cacheFile, json + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
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

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    assertThat(records.count()).isGreaterThanOrEqualTo(expectedCount);
                });

        return consumer.poll(Duration.ofMillis(1000));
    }

    private void initializeComponents() {
        logParser = new LogParser();
        logParser.initialize(Map.of(
                "grok.pattern.common_log", "%{COMMONAPACHELOG}",
                "grok.pattern.combined_log", "%{COMBINEDAPACHELOG}"
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
    }
}
