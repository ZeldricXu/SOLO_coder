package com.solocoder.platform.logging.cache;

import com.solocoder.platform.logging.model.LogLevelConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

@Slf4j
public class MultiLevelCacheManager {

    private final L1LocalCache l1Cache;
    private final L2DistributedCache l2Cache;

    public MultiLevelCacheManager(L1LocalCache l1Cache, L2DistributedCache l2Cache) {
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
        log.info("Multi-level cache manager initialized");
    }

    public Optional<LogLevelConfig> get(String loggerName) {
        Optional<LogLevelConfig> result = l1Cache.get(loggerName);
        if (result.isPresent()) {
            return result;
        }
        result = l2Cache.get(loggerName);
        result.ifPresent(config -> l1Cache.put(loggerName, config));
        return result;
    }

    public void put(String loggerName, LogLevelConfig config) {
        l1Cache.put(loggerName, config);
        l2Cache.put(loggerName, config);
        l2Cache.publishInvalidation(loggerName);
        log.info("Multi-level cache put: logger={}, level={}", loggerName, config.getLevel());
    }

    public void invalidate(String loggerName) {
        l1Cache.invalidate(loggerName);
        l2Cache.invalidate(loggerName);
        l2Cache.publishInvalidation(loggerName);
        log.info("Multi-level cache invalidated: logger={}", loggerName);
    }

    public void invalidateAll() {
        l1Cache.invalidateAll();
        l2Cache.invalidateAll();
        log.info("Multi-level cache cleared all entries");
    }

    public Map<String, LogLevelConfig> getAll() {
        Map<String, LogLevelConfig> l2All = l2Cache.getAll();
        for (Map.Entry<String, LogLevelConfig> entry : l2All.entrySet()) {
            l1Cache.put(entry.getKey(), entry.getValue());
        }
        return l2All;
    }

    public void onRemoteInvalidation(String loggerName) {
        l1Cache.invalidate(loggerName);
        log.info("Remote invalidation received: logger={}", loggerName);
    }

    public L1LocalCache getL1Cache() {
        return l1Cache;
    }

    public L2DistributedCache getL2Cache() {
        return l2Cache;
    }
}
