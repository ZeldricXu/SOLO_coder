package com.scheduler.scheduler.config;

import com.scheduler.scheduler.cache.TaskCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.cache.enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig {

    private final TaskCacheService taskCacheService;

    @Scheduled(initialDelay = 5000, fixedDelay = 3600000)
    public void scheduleCacheWarmUp() {
        if (!taskCacheService.isWarmed()) {
            taskCacheService.warmUp();
        }
    }

    @Scheduled(cron = "0 0 1 * * ?")
    public void scheduleCacheCleanup() {
        taskCacheService.invalidateExpiredEntries();
    }
}
