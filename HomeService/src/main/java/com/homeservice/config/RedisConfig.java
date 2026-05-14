package com.homeservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "homeservice.redis")
public class RedisConfig {

    private boolean enabled = false;
    private String host = "localhost";
    private int port = 6379;
    private String password = "";
    private int database = 0;
    private QueueConfig queue = new QueueConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getDatabase() {
        return database;
    }

    public void setDatabase(int database) {
        this.database = database;
    }

    public QueueConfig getQueue() {
        return queue;
    }

    public void setQueue(QueueConfig queue) {
        this.queue = queue;
    }

    public static class QueueConfig {
        private String settlementQueueKey = "homeservice:settlement:queue";
        private String processingKey = "homeservice:settlement:processing";
        private String failedKey = "homeservice:settlement:failed";
        private int pollIntervalMs = 1000;
        private int maxProcessingTimeMs = 300000;
        private int retryDelayMs = 60000;
        private int maxRetries = 3;

        public String getSettlementQueueKey() {
            return settlementQueueKey;
        }

        public void setSettlementQueueKey(String settlementQueueKey) {
            this.settlementQueueKey = settlementQueueKey;
        }

        public String getProcessingKey() {
            return processingKey;
        }

        public void setProcessingKey(String processingKey) {
            this.processingKey = processingKey;
        }

        public String getFailedKey() {
            return failedKey;
        }

        public void setFailedKey(String failedKey) {
            this.failedKey = failedKey;
        }

        public int getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(int pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getMaxProcessingTimeMs() {
            return maxProcessingTimeMs;
        }

        public void setMaxProcessingTimeMs(int maxProcessingTimeMs) {
            this.maxProcessingTimeMs = maxProcessingTimeMs;
        }

        public int getRetryDelayMs() {
            return retryDelayMs;
        }

        public void setRetryDelayMs(int retryDelayMs) {
            this.retryDelayMs = retryDelayMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }
}
