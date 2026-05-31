package com.tracetopology.core.resource;

import com.tracetopology.common.exception.BaseException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ResourceManager {

    private final Semaphore semaphore;
    private final int totalSlots;

    public ResourceManager(int poolSize) {
        this.totalSlots = poolSize;
        this.semaphore = new Semaphore(poolSize);
    }

    public Resource acquire(long timeoutMs) {
        try {
            boolean acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BaseException("RESOURCE_TIMEOUT", "资源获取超时，池大小: " + totalSlots);
            }
            return new Resource(this);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BaseException("RESOURCE_INTERRUPTED", "资源获取被中断", e);
        }
    }

    public void release(Resource resource) {
        if (resource != null && !resource.isReleased()) {
            semaphore.release();
            resource.markReleased();
            log.debug("资源已释放，当前可用: {}", semaphore.availablePermits());
        }
    }

    public int getAvailableSlots() {
        return semaphore.availablePermits();
    }

    public int getTotalSlots() {
        return totalSlots;
    }

    @Data
    @AllArgsConstructor
    public static class Resource {
        private final ResourceManager manager;
        private boolean released;

        public Resource(ResourceManager manager) {
            this.manager = manager;
            this.released = false;
        }

        public void markReleased() {
            this.released = true;
        }
    }
}
