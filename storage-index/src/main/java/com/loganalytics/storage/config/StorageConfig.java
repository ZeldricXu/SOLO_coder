package com.loganalytics.storage.config;

import com.loganalytics.common.config.AppConfig;

import java.time.Duration;

public class StorageConfig {
    private String applicationId;
    private String bootstrapServers;
    private String inputTopic;

    private String minioEndpoint;
    private String minioAccessKey;
    private String minioSecretKey;
    private String minioBucketName;
    private int minioPartSize;
    private int minioUploadThreads;
    private Duration minioUploadInterval;

    private String postgresUrl;
    private String postgresUser;
    private String postgresPassword;
    private int postgresPoolSize;

    private int batchSize;
    private Duration flushInterval;
    private int maxQueueSize;

    private boolean enableMinio;
    private boolean enablePostgres;
    private boolean enableFullTextSearch;

    public StorageConfig() {}

    public static StorageConfig fromAppConfig(AppConfig config) {
        StorageConfig sc = new StorageConfig();

        sc.setApplicationId(config.getString("storage.application.id", "storage-index"));
        sc.setBootstrapServers(config.getString("kafka.bootstrap.servers", "localhost:9092"));
        sc.setInputTopic(config.getString("storage.input.topic", "archive-logs"));

        sc.setMinioEndpoint(config.getString("minio.endpoint", "http://localhost:9000"));
        sc.setMinioAccessKey(config.getString("minio.access.key", "minioadmin"));
        sc.setMinioSecretKey(config.getString("minio.secret.key", "minioadmin"));
        sc.setMinioBucketName(config.getString("minio.bucket", "logs-archive"));
        sc.setMinioPartSize(config.getInt("minio.part.size", 10 * 1024 * 1024));
        sc.setMinioUploadThreads(config.getInt("minio.upload.threads", 4));
        sc.setMinioUploadInterval(Duration.ofSeconds(config.getInt("minio.upload.interval.seconds", 30)));

        sc.setPostgresUrl(config.getString("postgres.url", "jdbc:postgresql://localhost:5432/loganalytics"));
        sc.setPostgresUser(config.getString("postgres.user", "postgres"));
        sc.setPostgresPassword(config.getString("postgres.password", "postgres"));
        sc.setPostgresPoolSize(config.getInt("postgres.pool.size", 10));

        sc.setBatchSize(config.getInt("storage.batch.size", 1000));
        sc.setFlushInterval(Duration.ofSeconds(config.getInt("storage.flush.interval.seconds", 5)));
        sc.setMaxQueueSize(config.getInt("storage.max.queue.size", 100000));

        sc.setEnableMinio(config.getBoolean("storage.minio.enabled", true));
        sc.setEnablePostgres(config.getBoolean("storage.postgres.enabled", true));
        sc.setEnableFullTextSearch(config.getBoolean("storage.fulltext.enabled", true));

        return sc;
    }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

    public String getInputTopic() { return inputTopic; }
    public void setInputTopic(String inputTopic) { this.inputTopic = inputTopic; }

    public String getMinioEndpoint() { return minioEndpoint; }
    public void setMinioEndpoint(String minioEndpoint) { this.minioEndpoint = minioEndpoint; }

    public String getMinioAccessKey() { return minioAccessKey; }
    public void setMinioAccessKey(String minioAccessKey) { this.minioAccessKey = minioAccessKey; }

    public String getMinioSecretKey() { return minioSecretKey; }
    public void setMinioSecretKey(String minioSecretKey) { this.minioSecretKey = minioSecretKey; }

    public String getMinioBucketName() { return minioBucketName; }
    public void setMinioBucketName(String minioBucketName) { this.minioBucketName = minioBucketName; }

    public int getMinioPartSize() { return minioPartSize; }
    public void setMinioPartSize(int minioPartSize) { this.minioPartSize = minioPartSize; }

    public int getMinioUploadThreads() { return minioUploadThreads; }
    public void setMinioUploadThreads(int minioUploadThreads) { this.minioUploadThreads = minioUploadThreads; }

    public Duration getMinioUploadInterval() { return minioUploadInterval; }
    public void setMinioUploadInterval(Duration minioUploadInterval) { this.minioUploadInterval = minioUploadInterval; }

    public String getPostgresUrl() { return postgresUrl; }
    public void setPostgresUrl(String postgresUrl) { this.postgresUrl = postgresUrl; }

    public String getPostgresUser() { return postgresUser; }
    public void setPostgresUser(String postgresUser) { this.postgresUser = postgresUser; }

    public String getPostgresPassword() { return postgresPassword; }
    public void setPostgresPassword(String postgresPassword) { this.postgresPassword = postgresPassword; }

    public int getPostgresPoolSize() { return postgresPoolSize; }
    public void setPostgresPoolSize(int postgresPoolSize) { this.postgresPoolSize = postgresPoolSize; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public Duration getFlushInterval() { return flushInterval; }
    public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }

    public int getMaxQueueSize() { return maxQueueSize; }
    public void setMaxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; }

    public boolean isEnableMinio() { return enableMinio; }
    public void setEnableMinio(boolean enableMinio) { this.enableMinio = enableMinio; }

    public boolean isEnablePostgres() { return enablePostgres; }
    public void setEnablePostgres(boolean enablePostgres) { this.enablePostgres = enablePostgres; }

    public boolean isEnableFullTextSearch() { return enableFullTextSearch; }
    public void setEnableFullTextSearch(boolean enableFullTextSearch) { this.enableFullTextSearch = enableFullTextSearch; }
}
