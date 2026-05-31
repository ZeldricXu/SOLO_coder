package com.solocoder.platform.logging.cache;

import com.solocoder.platform.logging.model.LogLevelConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
public class CacheWarmupStrategy {

    private final MultiLevelCacheManager cacheManager;
    private final List<LogLevelConfig> seedConfigs;

    public CacheWarmupStrategy(MultiLevelCacheManager cacheManager, List<LogLevelConfig> seedConfigs) {
        this.cacheManager = cacheManager;
        this.seedConfigs = seedConfigs;
    }

    public void warmup() {
        log.info("Starting cache warmup with {} seed configs", seedConfigs.size());
        for (LogLevelConfig config : seedConfigs) {
            cacheManager.put(config.getLoggerName(), config);
            log.debug("Warmed cache: logger={}, level={}", config.getLoggerName(), config.getLevel());
        }
        log.info("Cache warmup completed");
    }

    public void warmupFromRemote() {
        log.info("Starting cache warmup from L2 distributed cache");
        Map<String, LogLevelConfig> all = cacheManager.getL2Cache().getAll();
        for (Map.Entry<String, LogLevelConfig> entry : all.entrySet()) {
            cacheManager.getL1Cache().put(entry.getKey(), entry.getValue());
            log.debug("Warmed L1 cache from L2: logger={}", entry.getKey());
        }
        log.info("L1 cache warmup from L2 completed: {} entries loaded", all.size());
    }
}
