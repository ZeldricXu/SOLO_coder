package com.logistics.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "logistics.notification")
public class NotificationConfig {

    private String redisQueueName = "notification:queue";
    private String redisFailedQueueName = "notification:failed";
    private int maxRetryCount = 3;
    private long retryDelayMs = 1000;
    private long failedCheckIntervalMs = 5000;
    private int maxWorkers = 5;
}
