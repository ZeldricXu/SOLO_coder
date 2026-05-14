package com.fitnesscenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fitness.plan")
public class PlanAsyncConfig {

    private String queueName = "fitness:plan:queue";
    private String processingQueueName = "fitness:plan:processing";
    private int maxRetry = 3;
    private int retryDelay = 100;
    private int workerInterval = 1000;

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public String getProcessingQueueName() {
        return processingQueueName;
    }

    public void setProcessingQueueName(String processingQueueName) {
        this.processingQueueName = processingQueueName;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public int getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(int retryDelay) {
        this.retryDelay = retryDelay;
    }

    public int getWorkerInterval() {
        return workerInterval;
    }

    public void setWorkerInterval(int workerInterval) {
        this.workerInterval = workerInterval;
    }
}
