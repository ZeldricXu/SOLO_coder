package com.configcenter.config.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "config-center.lock")
public class LockProperties {
    
    private Boolean enabled = true;
    private Long acquireTimeoutMillis = 5000L;
    private Long holdTimeoutMillis = 30000L;
    private Integer retryCount = 3;
    private Long retryIntervalMillis = 100L;
    private String lockPrefix = "config:lock:";
}
