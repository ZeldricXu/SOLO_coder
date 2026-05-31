package com.chainetl.modules.storage.service;

import com.chainetl.modules.storage.dto.BatchStoreContentRequest;
import com.chainetl.modules.storage.dto.BatchStoreResponse;
import com.chainetl.modules.storage.dto.StorageRecordResponse;
import com.chainetl.modules.storage.dto.StoreContentRequest;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class StorageBatchScheduler {

    private final StorageService storageService;
    private final MeterRegistry meterRegistry;

    private final ConcurrentLinkedQueue<PendingStoreRequest> pendingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong lastFlushTime = new AtomicLong(System.currentTimeMillis());

    private static final int MAX_BATCH_SIZE = 20;
    private static final long FLUSH_INTERVAL_MS = 500;

    private Counter batchStoreCounter;
    private Counter batchStoreItemCounter;

    @PostConstruct
    public void initMetrics() {
        batchStoreCounter = Counter.builder("storage.batch.store.count")
                .description("Number of batch store operations")
                .register(meterRegistry);
        batchStoreItemCounter = Counter.builder("storage.batch.store.items")
                .description("Number of items processed in batch stores")
                .register(meterRegistry);
    }

    public void enqueue(StoreContentRequest request) {
        pendingQueue.add(new PendingStoreRequest(request));
        log.debug("Enqueued store request, queue size: {}", pendingQueue.size());
    }

    @Scheduled(fixedDelayString = "${storage.batch.flush-interval-ms:500}", initialDelay = 1000)
    public void flushBatch() {
        if (pendingQueue.isEmpty()) {
            return;
        }

        int currentSize = pendingQueue.size();
        int batchSize = Math.min(currentSize, MAX_BATCH_SIZE);

        List<PendingStoreRequest> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            PendingStoreRequest req = pendingQueue.poll();
            if (req != null) {
                batch.add(req);
            }
        }

        if (batch.isEmpty()) {
            return;
        }

        log.info("Flushing storage batch of size {} (queue remaining: {})",
                batch.size(), pendingQueue.size());

        processBatch(batch);
    }

    @Timed(value = "storage.batch.process", description = "Time taken to process a storage batch")
    private void processBatch(List<PendingStoreRequest> batch) {
        try {
            String storageType = batch.get(0).getRequest().getStorageType();
            boolean allSameType = batch.stream()
                    .allMatch(r -> r.getRequest().getStorageType().equals(storageType));

            if (allSameType) {
                processMergedBatch(batch, storageType);
            } else {
                processIndividualRequests(batch);
            }
        } catch (Exception e) {
            log.error("Batch processing failed, falling back to individual processing: {}", e.getMessage());
            processIndividualRequests(batch);
        }
    }

    private void processMergedBatch(List<PendingStoreRequest> batch, String storageType) {
        List<BatchStoreContentRequest.BatchItem> items = batch.stream()
                .map(r -> BatchStoreContentRequest.BatchItem.builder()
                        .content(r.getRequest().getContent())
                        .metadata(r.getRequest().getMetadata())
                        .build())
                .toList();

        BatchStoreContentRequest batchRequest = BatchStoreContentRequest.builder()
                .storageType(storageType)
                .items(items)
                .pin(batch.get(0).getRequest().getPin())
                .build();

        try {
            BatchStoreResponse response = storageService.batchStoreContent(batchRequest).block();

            if (response != null) {
                batchStoreCounter.increment();
                batchStoreItemCounter.increment(response.getSuccessCount());

                for (int i = 0; i < batch.size(); i++) {
                    PendingStoreRequest pending = batch.get(i);
                    if (i < response.getResults().size()) {
                        BatchStoreResponse.BatchResultItem result = response.getResults().get(i);
                        if ("SUCCESS".equals(result.getStatus())) {
                            pending.getResult().complete(StorageRecordResponse.builder()
                                    .recordId(result.getRecordId())
                                    .storageType(storageType)
                                    .contentHash(result.getContentHash())
                                    .contentUrl(result.getContentUrl())
                                    .size(result.getSize())
                                    .build());
                        } else {
                            pending.getResult().completeExceptionally(
                                    new RuntimeException(result.getErrorMessage()));
                        }
                    } else {
                        pending.getResult().completeExceptionally(
                                new RuntimeException("Batch result index out of range"));
                    }
                }
            } else {
                processIndividualRequests(batch);
            }
        } catch (Exception e) {
            log.warn("Merged batch processing failed, falling back to individual: {}", e.getMessage());
            processIndividualRequests(batch);
        }
    }

    private void processIndividualRequests(List<PendingStoreRequest> batch) {
        for (PendingStoreRequest pending : batch) {
            try {
                StorageRecordResponse response = storageService.storeContent(pending.getRequest()).block();
                if (response != null) {
                    pending.getResult().complete(response);
                } else {
                    pending.getResult().completeExceptionally(
                            new RuntimeException("Store returned null"));
                }
            } catch (Exception e) {
                pending.getResult().completeExceptionally(e);
            }
        }
    }

    public int getPendingQueueSize() {
        return pendingQueue.size();
    }

    public Mono<StorageRecordResponse> enqueueAndGet(StoreContentRequest request) {
        PendingStoreRequest pending = new PendingStoreRequest(request);
        pendingQueue.add(pending);
        return Mono.fromFuture(pending.getResult());
    }

    private static class PendingStoreRequest {
        private final StoreContentRequest request;
        private final java.util.concurrent.CompletableFuture<StorageRecordResponse> result;

        public PendingStoreRequest(StoreContentRequest request) {
            this.request = request;
            this.result = new java.util.concurrent.CompletableFuture<>();
        }

        public StoreContentRequest getRequest() {
            return request;
        }

        public java.util.concurrent.CompletableFuture<StorageRecordResponse> getResult() {
            return result;
        }
    }
}
