package com.meeting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "meeting.invite")
public class InviteConfig {

    private String queueName = "meeting:invite:queue";
    private String processingSetName = "meeting:invite:processing";
    private RetryConfig retry = new RetryConfig();
    private WorkerConfig worker = new WorkerConfig();

    @Data
    public static class RetryConfig {
        private int maxRetryCount = 3;
        private long retryDelayMs = 1000;
        private int backoffMultiplier = 2;
    }

    @Data
    public static class WorkerConfig {
        private boolean enabled = true;
        private long pollIntervalMs = 500;
        private int batchSize = 10;
    }
}
