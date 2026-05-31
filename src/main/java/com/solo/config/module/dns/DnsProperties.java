package com.solo.config.module.dns;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "dns.proxy")
public class DnsProperties {

    private boolean enabled = true;
    private int port = 5353;
    private List<Upstream> upstreams = new ArrayList<>();
    private Cache cache = new Cache();

    @Data
    public static class Upstream {
        private String host;
        private int port = 53;
        private int priority = 1;
        private int timeout = 5000;
    }

    @Data
    public static class Cache {
        private int ttl = 300;
        private int maxSize = 10000;
        private long cleanupIntervalMs = 600000;
        private long maxCleanupIntervalMs = 3600000;
    }

    private Plugin plugin = new Plugin();

    @Data
    public static class Plugin {
        private long resolveTimeoutMs = 5000;
    }
}
