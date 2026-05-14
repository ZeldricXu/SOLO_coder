package com.assetinventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "inventory.detection")
public class DetectionConfig {

    private boolean enabled = true;
    private RedisConfig redis = new RedisConfig();
    private WorkerConfig worker = new WorkerConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RedisConfig getRedis() {
        return redis;
    }

    public void setRedis(RedisConfig redis) {
        this.redis = redis;
    }

    public WorkerConfig getWorker() {
        return worker;
    }

    public void setWorker(WorkerConfig worker) {
        this.worker = worker;
    }

    public static class RedisConfig {
        private String queueName = "inventory:detection:queue";
        private String processingSet = "inventory:detection:processing";
        private String retryQueueName = "inventory:detection:retry";
        private int maxRetries = 3;
        private int retryDelaySeconds = 60;

        public String getQueueName() {
            return queueName;
        }

        public void setQueueName(String queueName) {
            this.queueName = queueName;
        }

        public String getProcessingSet() {
            return processingSet;
        }

        public void setProcessingSet(String processingSet) {
            this.processingSet = processingSet;
        }

        public String getRetryQueueName() {
            return retryQueueName;
        }

        public void setRetryQueueName(String retryQueueName) {
            this.retryQueueName = retryQueueName;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public int getRetryDelaySeconds() {
            return retryDelaySeconds;
        }

        public void setRetryDelaySeconds(int retryDelaySeconds) {
            this.retryDelaySeconds = retryDelaySeconds;
        }
    }

    public static class WorkerConfig {
        private int threadCount = 2;
        private int pollTimeoutMs = 500;

        public int getThreadCount() {
            return threadCount;
        }

        public void setThreadCount(int threadCount) {
            this.threadCount = threadCount;
        }

        public int getPollTimeoutMs() {
            return pollTimeoutMs;
        }

        public void setPollTimeoutMs(int pollTimeoutMs) {
            this.pollTimeoutMs = pollTimeoutMs;
        }
    }
}
