package com.loganalytics.test.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public class LogAnalyticsContainers {

    public static KafkaContainer createKafkaContainer() {
        return newKafkaContainer();
    }

    public static KafkaContainer newKafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
                .withEnv("KAFKA_NUM_PARTITIONS", "6");
    }

    public static PostgreSQLContainer<?> createPostgreSQLContainer() {
        return newPostgreSQLContainer();
    }

    public static PostgreSQLContainer<?> newPostgreSQLContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
                .withDatabaseName("loganalytics")
                .withUsername("admin")
                .withPassword("password")
                .withInitScript("init-timescaledb.sql");
    }

    public static MinIOContainer createMinIOContainer() {
        return newMinIOContainer();
    }

    public static MinIOContainer newMinIOContainer() {
        return new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
                .withUserName("minioadmin")
                .withPassword("minioadmin");
    }

    @Deprecated
    public static GenericContainer<?> createTimescaleDBContainer() {
        return newTimescaleDBContainer();
    }

    public static PostgreSQLContainer<?> newTimescaleDBContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:latest-pg16"))
                .withDatabaseName("loganalytics")
                .withUsername("admin")
                .withPassword("password");
    }
}
