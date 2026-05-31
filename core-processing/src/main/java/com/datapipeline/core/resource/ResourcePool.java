package com.datapipeline.core.resource;

import com.datapipeline.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class ResourcePool {

    private final int maxSize;
    private final BlockingQueue<PooledResource> available;
    private final ConcurrentMap<String, PooledResource> acquired = new ConcurrentHashMap<>();
    private final AtomicInteger totalCreated = new AtomicInteger(0);
    private final ReentrantLock creationLock = new ReentrantLock();
    private final ResourceFactory factory;

    public ResourcePool(int maxSize, ResourceFactory factory) {
        this.maxSize = maxSize;
        this.factory = factory;
        this.available = new LinkedBlockingQueue<>(maxSize);
        log.info("ResourcePool initialized with maxSize={}", maxSize);
    }

    public PooledResource acquire(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        PooledResource resource = available.poll();
        if (resource != null) {
            resource.markAcquired();
            acquired.put(resource.getId(), resource);
            log.debug("Resource acquired from pool: id={}, available={}, acquired={}",
                    resource.getId(), available.size(), acquired.size());
            return resource;
        }

        if (totalCreated.get() < maxSize) {
            creationLock.lock();
            try {
                if (totalCreated.get() < maxSize) {
                    resource = factory.create();
                    totalCreated.incrementAndGet();
                    resource.markAcquired();
                    acquired.put(resource.getId(), resource);
                    log.debug("New resource created: id={}, totalCreated={}", resource.getId(), totalCreated.get());
                    return resource;
                }
            } finally {
                creationLock.unlock();
            }
        }

        resource = available.poll(timeout, unit);
        if (resource == null) {
            throw new TimeoutException("No resource available within timeout");
        }
        resource.markAcquired();
        acquired.put(resource.getId(), resource);
        return resource;
    }

    public void release(PooledResource resource) {
        if (resource == null) {
            return;
        }
        resource.markReleased();
        acquired.remove(resource.getId());
        if (resource.isValid()) {
            available.offer(resource);
            log.debug("Resource released: id={}, available={}, acquired={}",
                    resource.getId(), available.size(), acquired.size());
        } else {
            log.warn("Resource invalidated and removed from pool: id={}", resource.getId());
        }
    }

    public int getAvailableCount() {
        return available.size();
    }

    public int getAcquiredCount() {
        return acquired.size();
    }

    public int getTotalCreated() {
        return totalCreated.get();
    }

    public interface ResourceFactory {
        PooledResource create();
    }

}
