package com.datastandard.modules.core.resource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Slf4j
@Component
public class ResourcePoolManager {

    private final Semaphore resourcePool;
    private final Counter resourceAcquiredCounter;
    private final Counter resourceReleasedCounter;

    public ResourcePoolManager(MeterRegistry meterRegistry) {
        this.resourcePool = new Semaphore(100);
        this.resourceAcquiredCounter = Counter.builder("core.resource.acquired")
                .description("资源获取次数")
                .register(meterRegistry);
        this.resourceReleasedCounter = Counter.builder("core.resource.released")
                .description("资源释放次数")
                .register(meterRegistry);
    }

    public boolean tryAcquire() {
        boolean acquired = resourcePool.tryAcquire();
        if (acquired) {
            resourceAcquiredCounter.increment();
        } else {
            log.warn("资源池已满，无法获取资源");
        }
        return acquired;
    }

    public void release() {
        resourcePool.release();
        resourceReleasedCounter.increment();
    }

    public int availablePermits() {
        return resourcePool.availablePermits();
    }

    public int getPoolSize() {
        return 100;
    }
}
