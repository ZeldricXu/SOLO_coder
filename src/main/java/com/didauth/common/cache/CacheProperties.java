package com.didauth.common.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "didauth.cache")
public class CacheProperties {

    private Map<String, CacheConfig> configs = new HashMap<>();

    private boolean enabled = true;
    private boolean l1Enabled = true;
    private boolean l2Enabled = true;
    private boolean warmUpEnabled = false;

    @Data
    public static class CacheConfig {
        private Duration ttl = Duration.ofMinutes(10);
        private long maxSize = 10000;
        private boolean cacheNulls = false;
        private boolean warmUp = false;
    }

    public CacheConfig getConfig(String cacheName) {
        return configs.getOrDefault(cacheName, new CacheConfig());
    }
}
