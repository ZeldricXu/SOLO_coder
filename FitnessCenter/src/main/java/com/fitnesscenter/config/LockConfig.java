package com.fitnesscenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "fitness.lock")
public class LockConfig {

    private Map<String, Long> timeout = new HashMap<>();

    private static final long DEFAULT_TIMEOUT = 30000;

    public Map<String, Long> getTimeout() {
        return timeout;
    }

    public void setTimeout(Map<String, Long> timeout) {
        this.timeout = timeout;
    }

    public long getTimeoutByLevel(String memberLevel) {
        if (memberLevel == null || memberLevel.isEmpty()) {
            return DEFAULT_TIMEOUT;
        }
        return timeout.getOrDefault(memberLevel.toLowerCase(), DEFAULT_TIMEOUT);
    }

    public long getVipTimeout() {
        return timeout.getOrDefault("vip", 10000L);
    }

    public long getRegularTimeout() {
        return timeout.getOrDefault("regular", 30000L);
    }

    public long getPlatinumTimeout() {
        return timeout.getOrDefault("platinum", 5000L);
    }

    public boolean isLevelConfigured(String memberLevel) {
        return memberLevel != null && timeout.containsKey(memberLevel.toLowerCase());
    }
}
