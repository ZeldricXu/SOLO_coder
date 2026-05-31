package com.solocoder.dns.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "dns.platform")
public class PlatformProperties {
    private DnsConfig dns = new DnsConfig();
    private CacheConfig cache = new CacheConfig();
    private ThreadPoolConfig threadPool = new ThreadPoolConfig();
    private SecurityConfig security = new SecurityConfig();
    private MonitoringConfig monitoring = new MonitoringConfig();

    @Data
    public static class DnsConfig {
        private int port = 53;
        private int timeoutMs = 5000;
        private int maxRetries = 3;
        private List<String> upstreamServers = new ArrayList<>();
    }

    @Data
    public static class CacheConfig {
        private int maxSize = 10000;
        private int expireAfterWriteMinutes = 5;
        private int expireAfterAccessMinutes = 2;
    }

    @Data
    public static class ThreadPoolConfig {
        private int coreSize = 10;
        private int maxSize = 50;
        private int queueCapacity = 1000;
    }

    @Data
    public static class SecurityConfig {
        private boolean mtlsEnabled = false;
        private String certPath = "certs/";
    }

    @Data
    public static class MonitoringConfig {
        private boolean metricsEnabled = true;
        private boolean tracingEnabled = true;
        private int metricsExportIntervalSeconds = 30;
    }
}
