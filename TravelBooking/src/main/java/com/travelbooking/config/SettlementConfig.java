package com.travelbooking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "settlement")
public class SettlementConfig {

    private String redisQueueName = "travel:settlement:queue";
    private int maxRetryAttempts = 3;
    private long retryDelayMs = 1000;
    private int workerPoolSize = 4;
    private boolean persistenceEnabled = true;

    public String getRetryQueueName() {
        return redisQueueName + ":retry";
    }

    public String getDeadLetterQueueName() {
        return redisQueueName + ":dead_letter";
    }

    public String getProcessingSetName() {
        return redisQueueName + ":processing";
    }
}
