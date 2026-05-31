package com.datapipeline.core.resource;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

@Slf4j
public class HighPerformanceResourcePool {

    private final int maxSize;
    private final ResourceFactory factory;
    private final ConcurrentLinkedDeque<PooledResource> available;
    private final ConcurrentHashMap<String, PooledResource> acquired;
    private final AtomicInteger totalCreated;
    private final Semaphore creationPermit;

    public HighPerformanceResourcePool(int maxSize, ResourceFactory factory) {
        this.maxSize = maxSize;
        this.factory = factory;
        this.available = new ConcurrentLinkedDeque<>();
        this.acquired = new ConcurrentHashMap<>(maxSize, 0.75f, 1);
        this.totalCreated = new AtomicInteger(0);
        this.creationPermit = new Semaphore(1);
        log.info("HighPerformanceResourcePool initialized with maxSize={}", maxSize);
    }

    public PooledResource acquire(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);

        PooledResource resource = available.pollFirst();
        if (resource != null) {
            return doAcquire(resource);
        }

        resource = tryCreateNew();
        if (resource != null) {
            return resource;
        }

        long remainingNanos = deadlineNanos - System.nanoTime();
        while (remainingNanos > 0) {
            resource = available.pollFirst();
            if (resource != null) {
                return doAcquire(resource);
            }

            resource = tryCreateNew();
            if (resource != null) {
                return resource;
            }

            LockSupport.parkNanos(Math.min(remainingNanos, TimeUnit.MICROSECONDS.toNanos(10)));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            remainingNanos = deadlineNanos - System.nanoTime();
        }

        throw new TimeoutException("No resource available within timeout");
    }

    private PooledResource tryCreateNew() {
        if (totalCreated.get() >= maxSize) {
            return null;
        }
        if (!creationPermit.tryAcquire()) {
            return null;
        }
        try {
            if (totalCreated.get() >= maxSize) {
                return null;
            }
            PooledResource resource = factory.create();
            totalCreated.incrementAndGet();
            return doAcquire(resource);
        } finally {
            creationPermit.release();
        }
    }

    private PooledResource doAcquire(PooledResource resource) {
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
            available.offerFirst(resource);
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
