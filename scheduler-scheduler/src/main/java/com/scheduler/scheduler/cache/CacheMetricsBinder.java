package com.scheduler.scheduler.cache;

import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheMetricsBinder {

    private final TaskCacheService taskCacheService;
    private final MeterRegistry meterRegistry;

    private final AtomicLong l1HitCount = new AtomicLong(0);
    private final AtomicLong l1MissCount = new AtomicLong(0);
    private final AtomicLong l2HitCount = new AtomicLong(0);
    private final AtomicLong l2MissCount = new AtomicLong(0);

    @PostConstruct
    public void bindMetrics() {
        Gauge.builder("scheduler_cache_l1_size", taskCacheService, TaskCacheService::getL1Size)
                .description("L1 cache size")
                .register(meterRegistry);

        Gauge.builder("scheduler_cache_l2_size", taskCacheService, TaskCacheService::getL2Size)
                .description("L2 cache size")
                .register(meterRegistry);

        Gauge.builder("scheduler_cache_l1_hit_rate", taskCacheService,
                        service -> service.getL1Stats().hitRate() * 100)
                .description("L1 cache hit rate percentage")
                .register(meterRegistry);

        Gauge.builder("scheduler_cache_l1_eviction_count", taskCacheService,
                        service -> service.getL1Stats().evictionCount())
                .description("L1 cache eviction count")
                .register(meterRegistry);

        Gauge.builder("scheduler_cache_warmed", taskCacheService,
                        service -> service.isWarmed() ? 1 : 0)
                .description("Whether cache has been warmed up")
                .register(meterRegistry);

        log.info("Scheduler cache metrics bound to MeterRegistry");
    }

    public void recordL1Hit() {
        l1HitCount.incrementAndGet();
    }

    public void recordL1Miss() {
        l1MissCount.incrementAndGet();
    }

    public void recordL2Hit() {
        l2HitCount.incrementAndGet();
    }

    public void recordL2Miss() {
        l2MissCount.incrementAndGet();
    }
}
