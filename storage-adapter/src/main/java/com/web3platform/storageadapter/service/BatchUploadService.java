package com.web3platform.storageadapter.service;

import com.web3platform.storageadapter.config.StorageConfig;
import com.web3platform.storageadapter.model.BatchUploadItem;
import com.web3platform.storageadapter.model.BatchUploadRequest;
import com.web3platform.storageadapter.model.BatchUploadResult;
import com.web3platform.storageadapter.model.StorageUploadRequest;
import com.web3platform.storageadapter.model.StorageUploadResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchUploadService {

    private final StorageService storageService;
    private final StorageConfig storageConfig;

    private ExecutorService executorService;

    @PostConstruct
    public void init() {
        int poolSize = Math.max(storageConfig.getMaxConcurrentUploads(), 2);
        this.executorService = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "batch-upload-%d".formatted(System.currentTimeMillis()));
            t.setDaemon(true);
            return t;
        });
        log.info("BatchUploadService initialized with pool size: {}", poolSize);
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("BatchUploadService shutdown completed");
    }

    @NonNull
    public BatchUploadResult batchUpload(@NonNull BatchUploadRequest request) {
        int concurrency = Math.max(1,
                Math.min(request.getConcurrency(), storageConfig.getMaxConcurrentUploads()));
        List<BatchUploadItem> items = request.getItems();

        if (items == null || items.isEmpty()) {
            return buildEmptyResult();
        }

        List<StorageUploadResponse> results = Collections.synchronizedList(new ArrayList<>(items.size()));
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        Semaphore semaphore = new Semaphore(concurrency);

        List<Future<?>> futures = new ArrayList<>(items.size());
        submitUploadTasks(items, request, concurrency, results, errors, successCount, failedCount, semaphore, futures);
        waitForCompletion(futures);

        return BatchUploadResult.builder()
                .results(results)
                .totalCount(items.size())
                .successCount(successCount.get())
                .failedCount(failedCount.get())
                .errors(errors)
                .build();
    }

    private void submitUploadTasks(List<BatchUploadItem> items, BatchUploadRequest request, int concurrency,
                                   List<StorageUploadResponse> results, List<String> errors,
                                   AtomicInteger successCount, AtomicInteger failedCount,
                                   Semaphore semaphore, List<Future<?>> futures) {
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final BatchUploadItem item = items.get(i);
            acquireSemaphore(semaphore, index);

            futures.add(executorService.submit(() -> {
                try {
                    StorageUploadResponse response = processSingleUpload(item, request);
                    results.add(index, response);
                    successCount.incrementAndGet();
                    log.debug("Batch upload item {} succeeded: cid={}", index, response.getCid());
                } catch (Exception e) {
                    handleUploadFailure(index, item, e, results, errors, failedCount);
                } finally {
                    semaphore.release();
                }
            }));
        }
    }

    private void acquireSemaphore(Semaphore semaphore, int index) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Batch upload interrupted at item " + index, e);
        }
    }

    private StorageUploadResponse processSingleUpload(BatchUploadItem item, BatchUploadRequest request) {
        StorageUploadRequest uploadRequest = StorageUploadRequest.builder()
                .data(item.getData())
                .fileName(item.getFileName())
                .storageType(request.getStorageType())
                .pin(request.isPin())
                .metadata(item.getMetadata())
                .build();
        return storageService.upload(uploadRequest);
    }

    private void handleUploadFailure(int index, BatchUploadItem item, Exception e,
                                     List<StorageUploadResponse> results, List<String> errors,
                                     AtomicInteger failedCount) {
        failedCount.incrementAndGet();
        String errorMsg = "Item " + index + " (" + item.getFileName() + "): " + e.getMessage();
        errors.add(errorMsg);
        results.add(index, null);
        log.warn("Batch upload item {} failed: {}", index, e.getMessage());
    }

    private void waitForCompletion(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Batch upload interrupted", e);
            } catch (Exception e) {
                log.error("Batch upload task execution error", e);
            }
        }
    }

    private BatchUploadResult buildEmptyResult() {
        return BatchUploadResult.builder()
                .results(Collections.emptyList())
                .totalCount(0)
                .successCount(0)
                .failedCount(0)
                .errors(Collections.emptyList())
                .build();
    }
}
