package com.datamasker.domain.privacy.batch;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class BatchRequestAccumulator {

    private final ConcurrentLinkedQueue<BatchPrivacyRequest.BatchItem> queue = new ConcurrentLinkedQueue<>();

    private final AtomicLong totalProcessed = new AtomicLong(0);

    private final AtomicInteger flushCount = new AtomicInteger(0);

    private volatile long lastFlushTime = System.currentTimeMillis();

    private int batchSizeThreshold = 100;

    private long flushIntervalMs = 500;

    public void addRequest(BatchPrivacyRequest.BatchItem item) {
        queue.offer(item);
    }

    public List<BatchPrivacyRequest.BatchItem> drain() {
        List<BatchPrivacyRequest.BatchItem> items = new ArrayList<>();
        BatchPrivacyRequest.BatchItem item;
        while ((item = queue.poll()) != null) {
            items.add(item);
        }
        totalProcessed.addAndGet(items.size());
        flushCount.incrementAndGet();
        lastFlushTime = System.currentTimeMillis();
        return items;
    }

    public boolean shouldFlush() {
        return queue.size() >= batchSizeThreshold ||
                (System.currentTimeMillis() - lastFlushTime >= flushIntervalMs && !queue.isEmpty());
    }

    public int getPendingItems() {
        return queue.size();
    }

    public long getTotalProcessed() {
        return totalProcessed.get();
    }

    public long getLastFlushTime() {
        return lastFlushTime;
    }

    public int getFlushCount() {
        return flushCount.get();
    }

    public void setBatchSizeThreshold(int threshold) {
        this.batchSizeThreshold = threshold;
    }

    public void setFlushIntervalMs(long intervalMs) {
        this.flushIntervalMs = intervalMs;
    }
}
