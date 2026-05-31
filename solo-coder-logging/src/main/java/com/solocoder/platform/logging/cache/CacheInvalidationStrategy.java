package com.solocoder.platform.logging.cache;

import com.solocoder.platform.logging.model.LogLevelConfig;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CacheInvalidationStrategy {

    private final MultiLevelCacheManager cacheManager;
    private final ScheduledExecutorService scheduler;
    private final long cleanupIntervalSeconds;

    public CacheInvalidationStrategy(MultiLevelCacheManager cacheManager, long cleanupIntervalSeconds) {
        this.cacheManager = cacheManager;
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-invalidation");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::cleanupExpired, cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS);
        log.info("Cache invalidation strategy started: interval={}s", cleanupIntervalSeconds);
    }

    public void stop() {
        scheduler.shutdown();
        log.info("Cache invalidation strategy stopped");
    }

    private void cleanupExpired() {
        try {
            Map<String, LogLevelConfig> allL1 = cacheManager.getL1Cache().getAll();
            LocalDateTime now = LocalDateTime.now();
            for (Map.Entry<String, LogLevelConfig> entry : allL1.entrySet()) {
                LogLevelConfig config = entry.getValue();
                if (config.getExpiresAt() != null && now.isAfter(config.getExpiresAt())) {
                    cacheManager.getL1Cache().invalidate(entry.getKey());
                    log.info("Expired L1 cache entry removed: logger={}", entry.getKey());
                }
            }
        } catch (Exception e) {
            log.error("Error during cache cleanup", e);
        }
    }

    public void invalidateByScope(String scope) {
        Map<String, LogLevelConfig> all = cacheManager.getL1Cache().getAll();
        for (Map.Entry<String, LogLevelConfig> entry : all.entrySet()) {
            if (scope.equals(entry.getValue().getScope())) {
                cacheManager.invalidate(entry.getKey());
                log.info("Invalidated by scope: logger={}, scope={}", entry.getKey(), scope);
            }
        }
    }
}
