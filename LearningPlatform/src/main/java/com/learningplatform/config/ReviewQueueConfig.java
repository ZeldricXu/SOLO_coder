package com.learningplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "learning.review")
public class ReviewQueueConfig {

    private String queueName = "review_task_queue";
    private Worker worker = new Worker();
    private int maxRetryAttempts = 3;
    private long retryDelayMs = 1000;

    @Data
    public static class Worker {
        private boolean enabled = true;
        private long pollIntervalMs = 1000;
        private int batchSize = 10;
    }

    public String getProcessingQueueKey() {
        return queueName + ":processing";
    }

    public String getRetryQueueKey() {
        return queueName + ":retry";
    }

    public String getDeadLetterQueueKey() {
        return queueName + ":dlq";
    }

    public String getTaskKeyPrefix() {
        return queueName + ":task:";
    }
}
