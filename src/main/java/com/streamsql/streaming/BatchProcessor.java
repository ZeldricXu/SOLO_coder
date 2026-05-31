package com.streamsql.streaming;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchProcessor {

    private final StreamingConfig config;

    private final ExecutorService executorService = Executors.newWorkStealingPool();
    private final Semaphore semaphore = new Semaphore(100);

    public <T> List<List<T>> partition(List<T> data, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < data.size(); i += batchSize) {
            batches.add(data.subList(i, Math.min(i + batchSize, data.size())));
        }
        return batches;
    }

    public <T, R> List<R> processBatch(List<T> data, Function<List<T>, R> processor) {
        return processBatch(data, processor, config.getBatchSize());
    }

    public <T, R> List<R> processBatch(List<T> data, Function<List<T>, R> processor, int batchSize) {
        List<List<T>> batches = partition(data, batchSize);
        List<CompletableFuture<R>> futures = new ArrayList<>();

        for (List<T> batch : batches) {
            CompletableFuture<R> future = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            if (config.isEnableBackpressure()) {
                                semaphore.acquire();
                            }
                            try {
                                return processor.apply(batch);
                            } finally {
                                if (config.isEnableBackpressure()) {
                                    semaphore.release();
                                }
                            }
                        } catch (Exception e) {
                            log.error("Batch processing failed", e);
                            throw new RuntimeException(e);
                        }
                    },
                    executorService
            );
            futures.add(future);
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    public <T> void processBatchAsync(List<T> data, Consumer<List<T>> processor, int batchSize) {
        List<List<T>> batches = partition(data, batchSize);

        for (List<T> batch : batches) {
            CompletableFuture.runAsync(
                    () -> {
                        try {
                            if (config.isEnableBackpressure()) {
                                semaphore.acquire();
                            }
                            try {
                                processor.accept(batch);
                            } finally {
                                if (config.isEnableBackpressure()) {
                                    semaphore.release();
                                }
                            }
                        } catch (Exception e) {
                            log.error("Async batch processing failed", e);
                        }
                    },
                    executorService
            );
        }
    }

    public <T, R> List<R> processWithRetry(List<T> data, Function<List<T>, R> processor, int batchSize) {
        List<List<T>> batches = partition(data, batchSize);
        List<R> results = new ArrayList<>();

        for (List<T> batch : batches) {
            R result = executeWithRetry(batch, processor);
            results.add(result);
        }

        return results;
    }

    private <T, R> R executeWithRetry(List<T> batch, Function<List<T>, R> processor) {
        Exception lastException = null;

        for (int attempt = 0; attempt < config.getMaxRetries(); attempt++) {
            try {
                return processor.apply(batch);
            } catch (Exception e) {
                lastException = e;
                log.warn("Batch processing failed on attempt {}/{}", attempt + 1, config.getMaxRetries(), e);
                if (attempt < config.getMaxRetries() - 1) {
                    try {
                        Thread.sleep(config.getRetryDelayMs() * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                }
            }
        }

        throw new RuntimeException("Batch processing failed after " + config.getMaxRetries() + " attempts", lastException);
    }

    public <T, R> List<R> processParallel(List<T> data, Function<T, R> processor) {
        List<CompletableFuture<R>> futures = new ArrayList<>();

        for (T item : data) {
            CompletableFuture<R> future = CompletableFuture.supplyAsync(
                    () -> processor.apply(item),
                    executorService
            );
            futures.add(future);
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
