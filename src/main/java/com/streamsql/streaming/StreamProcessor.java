package com.streamsql.streaming;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamProcessor<T> {

    private final StreamingConfig config;

    private final BlockingQueue<T> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    private Consumer<List<T>> batchProcessor;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> flushFuture;

    public void start(Consumer<List<T>> processor) {
        if (running.compareAndSet(false, true)) {
            this.batchProcessor = processor;
            this.scheduler = Executors.newSingleThreadScheduledExecutor();
            this.flushFuture = scheduler.scheduleAtFixedRate(
                    this::flush,
                    config.getFlushIntervalMs(),
                    config.getFlushIntervalMs(),
                    TimeUnit.MILLISECONDS
            );
            log.info("Stream processor started with flush interval: {}ms", config.getFlushIntervalMs());
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            flush();
            if (flushFuture != null) {
                flushFuture.cancel(false);
            }
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            log.info("Stream processor stopped. Processed: {}, Errors: {}", processedCount.get(), errorCount.get());
        }
    }

    public void add(T item) {
        if (!running.get()) {
            throw new IllegalStateException("Stream processor is not running");
        }

        if (queue.size() >= config.getQueueCapacity()) {
            log.warn("Queue capacity exceeded, dropping item");
            errorCount.incrementAndGet();
            return;
        }

        try {
            queue.offer(item, 100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorCount.incrementAndGet();
            log.warn("Failed to add item to queue, interrupted");
        }
    }

    public void addAll(List<T> items) {
        for (T item : items) {
            add(item);
        }
    }

    private void flush() {
        if (queue.isEmpty()) {
            return;
        }

        List<T> batch = new ArrayList<>();
        queue.drainTo(batch, config.getBatchSize());

        if (!batch.isEmpty()) {
            try {
                batchProcessor.accept(batch);
                processedCount.addAndGet(batch.size());
                log.debug("Flushed {} items, total processed: {}", batch.size(), processedCount.get());
            } catch (Exception e) {
                errorCount.addAndGet(batch.size());
                log.error("Error processing batch of size {}", batch.size(), e);
            }
        }
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public int getQueueSize() {
        return queue.size();
    }

    public boolean isRunning() {
        return running.get();
    }

    public StreamMetrics getMetrics() {
        return new StreamMetrics(
                processedCount.get(),
                errorCount.get(),
                queue.size(),
                running.get()
        );
    }

    public record StreamMetrics(
            long processedCount,
            long errorCount,
            int queueSize,
            boolean running
    ) {}
}
