package com.crm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "crm.category.queue")
public class CategoryQueueProperties {
    
    private String queueName = "crm:category:tasks";
    private String processingQueueName = "crm:category:processing";
    private String failedQueueName = "crm:category:failed";
    private int maxRetries = 3;
    private int retryDelayMs = 1000;
    private int pollIntervalMs = 500;
    private int processingTimeoutMs = 30000;
}
