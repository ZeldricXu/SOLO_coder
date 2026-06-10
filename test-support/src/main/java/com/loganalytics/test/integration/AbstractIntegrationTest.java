package com.loganalytics.test.integration;

import com.loganalytics.test.container.LogAnalyticsContainers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

@Testcontainers
public abstract class AbstractIntegrationTest {

    protected static KafkaContainer kafka;
    protected static PostgreSQLContainer<?> postgres;
    protected static MinIOContainer minio;

    protected static KafkaProducer<String, String> producer;
    protected static KafkaConsumer<String, String> consumer;

    @BeforeAll
    static void startContainers() {
        kafka = LogAnalyticsContainers.newKafkaContainer();
        postgres = LogAnalyticsContainers.newTimescaleDBContainer();
        minio = LogAnalyticsContainers.newMinIOContainer();

        kafka.start();
        postgres.start();
        minio.start();

        initializeKafkaClients();
        initializeDatabaseSchema();
    }

    @AfterAll
    static void stopContainers() {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (kafka != null) {
            kafka.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (minio != null) {
            minio.stop();
        }
    }

    private static void initializeKafkaClients() {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        producer = new KafkaProducer<>(producerProps);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(consumerProps);
    }

    private static void initializeDatabaseSchema() {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        )) {
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

                stmt.execute("SELECT create_hypertable('metrics', 'time', if_not_exists => TRUE)");

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS log_patterns (
                        id TEXT PRIMARY KEY,
                        template TEXT NOT NULL,
                        regex TEXT,
                        sample_count BIGINT DEFAULT 1,
                        first_seen TIMESTAMPTZ NOT NULL,
                        last_seen TIMESTAMPTZ NOT NULL,
                        service TEXT,
                        level TEXT,
                        is_novel BOOLEAN DEFAULT FALSE
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS anomaly_events (
                        id TEXT PRIMARY KEY,
                        type TEXT NOT NULL,
                        detected_at TIMESTAMPTZ NOT NULL,
                        severity TEXT,
                        level TEXT,
                        pattern_id TEXT,
                        service TEXT,
                        details JSONB
                    )
                    """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS alerts (
                        id TEXT PRIMARY KEY,
                        rule_id TEXT NOT NULL,
                        anomaly_id TEXT,
                        title TEXT NOT NULL,
                        message TEXT,
                        severity TEXT,
                        service TEXT,
                        created_at TIMESTAMPTZ NOT NULL,
                        status TEXT DEFAULT 'ACTIVE'
                    )
                    """);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    protected String getKafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    protected String getPostgresJdbcUrl() {
        return postgres.getJdbcUrl();
    }

    protected String getPostgresUsername() {
        return postgres.getUsername();
    }

    protected String getPostgresPassword() {
        return postgres.getPassword();
    }

    protected String getMinioEndpoint() {
        return minio.getS3URL();
    }

    protected String getMinioAccessKey() {
        return minio.getUserName();
    }

    protected String getMinioSecretKey() {
        return minio.getPassword();
    }

    protected Connection getPostgresConnection() throws Exception {
        return DriverManager.getConnection(
                getPostgresJdbcUrl(),
                getPostgresUsername(),
                getPostgresPassword()
        );
    }
}
