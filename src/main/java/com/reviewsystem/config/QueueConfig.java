package com.reviewsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "review.queue")
public class QueueConfig {

    private QueueItem audit = new QueueItem();
    private QueueItem sentiment = new QueueItem();

    public QueueItem getAudit() {
        return audit;
    }

    public void setAudit(QueueItem audit) {
        this.audit = audit;
    }

    public QueueItem getSentiment() {
        return sentiment;
    }

    public void setSentiment(QueueItem sentiment) {
        this.sentiment = sentiment;
    }

    public static class QueueItem {
        private String name;
        private int retryCount = 3;
        private long retryDelay = 5000;
        private boolean workerEnabled = true;
        private long workerInterval = 1000;
        private int batchSize = 10;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }

        public long getRetryDelay() {
            return retryDelay;
        }

        public void setRetryDelay(long retryDelay) {
            this.retryDelay = retryDelay;
        }

        public boolean isWorkerEnabled() {
            return workerEnabled;
        }

        public void setWorkerEnabled(boolean workerEnabled) {
            this.workerEnabled = workerEnabled;
        }

        public long getWorkerInterval() {
            return workerInterval;
        }

        public void setWorkerInterval(long workerInterval) {
            this.workerInterval = workerInterval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
