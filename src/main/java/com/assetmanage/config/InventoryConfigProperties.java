package com.assetmanage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "inventory")
public class InventoryConfigProperties {

    private AsyncConfig async = new AsyncConfig();

    @Data
    public static class AsyncConfig {
        private boolean enabled = true;
        private int poolSize = 5;
        private int queueCapacity = 1000;
        private int maxRetryCount = 3;
        private int retryDelayMs = 1000;
    }
}
