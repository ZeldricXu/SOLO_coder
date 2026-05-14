package com.taskscheduler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "taskscheduler.failhandler")
public class FailHandlerConfig {

    private boolean enableAsyncRetry = true;
    private int retryThreadPoolSize = 20;
    private int maxConcurrentRetries = 50;
    private int baseRetryDelaySeconds = 30;
    private int maxRetryDelaySeconds = 3600;
    private int retryScanIntervalSeconds = 10;
}
