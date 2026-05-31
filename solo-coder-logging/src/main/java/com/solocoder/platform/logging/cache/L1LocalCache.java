package com.solocoder.platform.logging.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.solocoder.platform.logging.model.LogLevelConfig;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class L1LocalCache {

    private final Cache<String, LogLevelConfig> cache;
    private final String nodeId;

    public L1LocalCache(String nodeId, long maxSize, Duration expireAfterWrite) {
        this.nodeId = nodeId;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireAfterWrite)
                .recordStats()
                .build();
        log.info("L1 local cache initialized: nodeId={}, maxSize={}, expireAfterWrite={}", nodeId, maxSize, expireAfterWrite);
    }

    public Optional<LogLevelConfig> get(String loggerName) {
        LogLevelConfig config = cache.getIfPresent(loggerName);
        if (config != null) {
            if (config.isExpired()) {
                cache.invalidate(loggerName);
                log.debug("L1 cache entry expired and removed: logger={}", loggerName);
                return Optional.empty();
            }
            log.debug("L1 cache hit: logger={}", loggerName);
            return Optional.of(config);
        }
        log.debug("L1 cache miss: logger={}", loggerName);
        return Optional.empty();
    }

    public void put(String loggerName, LogLevelConfig config) {
        cache.put(loggerName, config);
        log.debug("L1 cache put: logger={}, level={}", loggerName, config.getLevel());
    }

    public void invalidate(String loggerName) {
        cache.invalidate(loggerName);
        log.debug("L1 cache invalidated: logger={}", loggerName);
    }

    public void invalidateAll() {
        cache.invalidateAll();
        log.debug("L1 cache cleared all entries");
    }

    public Map<String, LogLevelConfig> getAll() {
        Map<String, LogLevelConfig> result = new ConcurrentHashMap<>();
        cache.asMap().forEach((key, value) -> {
            if (!value.isExpired()) {
                result.put(key, value);
            }
        });
        return result;
    }

    public com.github.benmanes.caffeine.cache.CacheStats stats() {
        return cache.stats();
    }

    public String getNodeId() {
        return nodeId;
    }
}
