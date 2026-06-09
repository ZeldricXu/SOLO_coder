package com.loganalytics.test.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public class LogAnalyticsContainers {

    public static KafkaContainer createKafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
                .withEnv("KAFKA_NUM_PARTITIONS", "6");
    }

    public static PostgreSQLContainer<?> createPostgreSQLContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
                .withDatabaseName("loganalytics")
                .withUsername("admin")
                .withPassword("password")
                .withInitScript("init-timescaledb.sql");
    }

    public static MinIOContainer createMinIOContainer() {
        return new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
                .withUserName("minioadmin")
                .withPassword("minioadmin");
    }

    public static GenericContainer<?> createTimescaleDBContainer() {
        return new GenericContainer<>(DockerImageName.parse("timescale/timescaledb:latest-pg16"))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_DB", "loganalytics")
                .withEnv("POSTGRES_USER", "admin")
                .withEnv("POSTGRES_PASSWORD", "password");
    }
}
