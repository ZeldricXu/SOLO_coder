package com.monitoring.trace.cache;

import com.monitoring.trace.model.TraceSpan;
import org.jctools.queues.MpmcArrayQueue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public final class ConcurrentSpanBuffer {

    private static final int DEFAULT_CAPACITY = 1024;

    private final Queue<TraceSpan> spanQueue;
    private final AtomicInteger size;
    private final int maxCapacity;

    public ConcurrentSpanBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentSpanBuffer(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + maxCapacity);
        }
        this.maxCapacity = maxCapacity;
        this.spanQueue = new MpmcArrayQueue<>(maxCapacity);
        this.size = new AtomicInteger(0);
    }

    public boolean add(TraceSpan span) {
        if (span == null) {
            return false;
        }

        int currentSize = size.get();
        if (currentSize >= maxCapacity) {
            evictOldest();
        }

        boolean added = spanQueue.offer(span);
        if (added) {
            size.incrementAndGet();
        }
        return added;
    }

    public List<TraceSpan> getSnapshot() {
        List<TraceSpan> spans = new ArrayList<>(size.get());
        spanQueue.forEach(spans::add);
        return spans;
    }

    public List<TraceSpan> getImmutableSnapshot() {
        return Collections.unmodifiableList(getSnapshot());
    }

    public int size() {
        return size.get();
    }

    public boolean isEmpty() {
        return size.get() == 0;
    }

    public void clear() {
        spanQueue.clear();
        size.set(0);
    }

    public boolean hasErrors() {
        if (isEmpty()) {
            return false;
        }
        return spanQueue.stream()
                .anyMatch(span -> Boolean.TRUE.equals(span.getError())
                        || (span.getHttpStatus() != null && span.getHttpStatus() >= 500));
    }

    public long getTotalDurationNanos() {
        if (isEmpty()) {
            return 0L;
        }
        return spanQueue.stream()
                .mapToLong(span -> span.getDurationNanos() != null ? span.getDurationNanos() : 0L)
                .sum();
    }

    public double getErrorRate() {
        int total = size.get();
        if (total == 0) {
            return 0.0;
        }
        long errorCount = spanQueue.stream()
                .filter(span -> Boolean.TRUE.equals(span.getError())
                        || (span.getHttpStatus() != null && span.getHttpStatus() >= 500))
                .count();
        return (double) errorCount / total;
    }

    private void evictOldest() {
        TraceSpan evicted = spanQueue.poll();
        if (evicted != null) {
            size.decrementAndGet();
        }
    }
}
