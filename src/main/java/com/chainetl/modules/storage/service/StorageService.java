package com.chainetl.modules.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.common.util.IdGenerator;
import com.chainetl.modules.storage.dto.*;
import com.chainetl.modules.storage.mapper.StorageRecordMapper;
import com.chainetl.modules.storage.model.StorageRecord;
import com.chainetl.modules.storage.provider.ArweaveStorageProvider;
import com.chainetl.modules.storage.provider.IpfsStorageProvider;
import com.github.benmanes.caffeine.cache.Cache;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final StorageRecordMapper storageRecordMapper;
    private final IpfsStorageProvider ipfsProvider;
    private final ArweaveStorageProvider arweaveProvider;
    private final Cache<String, Object> caffeineCache;
    private final MeterRegistry meterRegistry;

    private Counter storeCounter;
    private Counter storeSuccessCounter;
    private Counter storeFailureCounter;
    private Counter batchStoreCounter;
    private Counter batchStoreItemCounter;

    private static final String STORAGE_IPFS = "IPFS";
    private static final String STORAGE_ARWEAVE = "ARWEAVE";

    private static final String PIN_STATUS_PINNED = "PINNED";
    private static final String PIN_STATUS_UNPINNED = "UNPINNED";
    private static final String PIN_STATUS_PINNING = "PINNING";
    private static final String PIN_STATUS_NOT_APPLICABLE = "N/A";

    @PostConstruct
    public void initMetrics() {
        storeCounter = Counter.builder("storage.store.total")
                .description("Total storage store operations")
                .register(meterRegistry);
        storeSuccessCounter = Counter.builder("storage.store.success")
                .description("Successful storage store operations")
                .register(meterRegistry);
        storeFailureCounter = Counter.builder("storage.store.failure")
                .description("Failed storage store operations")
                .register(meterRegistry);
        batchStoreCounter = Counter.builder("storage.batch.total")
                .description("Total batch store operations")
                .register(meterRegistry);
        batchStoreItemCounter = Counter.builder("storage.batch.items")
                .description("Total items in batch store operations")
                .register(meterRegistry);
    }

    @Transactional
    @Retry(name = "storage", fallbackMethod = "storeContentFallback")
    public Mono<StorageRecordResponse> storeContent(StoreContentRequest request) {
        return Mono.fromCallable(() -> {
            storeCounter.increment();

            String storageType = request.getStorageType().toUpperCase();
            validateStorageType(storageType);

            String content = request.getContent();
            long size = content.getBytes(StandardCharsets.UTF_8).length;

            String contentHash = storeContentToProvider(storageType, content);

            String contentUrl = buildContentUrl(storageType, contentHash);

            String recordId = IdGenerator.generateStorageId();
            String pinStatus = PIN_STATUS_NOT_APPLICABLE;
            Instant pinnedAt = null;

            if (STORAGE_IPFS.equals(storageType) && Boolean.TRUE.equals(request.getPin())) {
                pinStatus = PIN_STATUS_PINNING;
                Boolean pinned = ipfsProvider.pinContent(contentHash).block();
                if (Boolean.TRUE.equals(pinned)) {
                    pinStatus = PIN_STATUS_PINNED;
                    pinnedAt = Instant.now();
                }
            } else if (STORAGE_IPFS.equals(storageType)) {
                pinStatus = PIN_STATUS_UNPINNED;
            }

            StorageRecord record = StorageRecord.builder()
                    .recordId(recordId)
                    .storageType(storageType)
                    .contentHash(contentHash)
                    .contentUrl(contentUrl)
                    .pinStatus(pinStatus)
                    .size(size)
                    .createdAt(Instant.now())
                    .pinnedAt(pinnedAt)
                    .metadata(request.getMetadata())
                    .build();

            storageRecordMapper.insert(record);
            log.info("Stored content to {} with hash: {}", storageType, contentHash);
            storeSuccessCounter.increment();

            return toResponse(record);
        }).onErrorResume(e -> {
            storeFailureCounter.increment();
            log.error("Store content failed: {}", e.getMessage());
            return Mono.error(e);
        });
    }

    @Transactional
    @Timed(value = "storage.batch.store", description = "Time taken to store content in batch")
    public Mono<BatchStoreResponse> batchStoreContent(BatchStoreContentRequest request) {
        return Mono.fromCallable(() -> {
            batchStoreCounter.increment();

            String storageType = request.getStorageType().toUpperCase();
            validateStorageType(storageType);

            List<BatchStoreResponse.BatchResultItem> results = new ArrayList<>();
            int successCount = 0;
            int failedCount = 0;

            List<BatchStoreContentRequest.BatchItem> items = request.getItems();
            log.info("Processing batch store: {} items to {}", items.size(), storageType);

            for (int i = 0; i < items.size(); i++) {
                BatchStoreContentRequest.BatchItem item = items.get(i);
                BatchStoreResponse.BatchResultItem resultItem = new BatchStoreResponse.BatchResultItem();
                resultItem.setIndex(i);

                try {
                    String content = item.getContent();
                    if (content == null || content.isEmpty()) {
                        resultItem.setStatus("FAILED");
                        resultItem.setErrorMessage("Content is null or empty");
                        failedCount++;
                        results.add(resultItem);
                        continue;
                    }

                    long size = content.getBytes(StandardCharsets.UTF_8).length;
                    String contentHash = storeContentToProvider(storageType, content);
                    String contentUrl = buildContentUrl(storageType, contentHash);

                    String recordId = IdGenerator.generateStorageId();
                    String pinStatus = PIN_STATUS_NOT_APPLICABLE;
                    Instant pinnedAt = null;

                    if (STORAGE_IPFS.equals(storageType) && Boolean.TRUE.equals(request.getPin())) {
                        pinStatus = PIN_STATUS_PINNING;
                        Boolean pinned = ipfsProvider.pinContent(contentHash).block();
                        if (Boolean.TRUE.equals(pinned)) {
                            pinStatus = PIN_STATUS_PINNED;
                            pinnedAt = Instant.now();
                        }
                    } else if (STORAGE_IPFS.equals(storageType)) {
                        pinStatus = PIN_STATUS_UNPINNED;
                    }

                    StorageRecord record = StorageRecord.builder()
                            .recordId(recordId)
                            .storageType(storageType)
                            .contentHash(contentHash)
                            .contentUrl(contentUrl)
                            .pinStatus(pinStatus)
                            .size(size)
                            .createdAt(Instant.now())
                            .pinnedAt(pinnedAt)
                            .metadata(item.getMetadata())
                            .build();

                    storageRecordMapper.insert(record);

                    resultItem.setStatus("SUCCESS");
                    resultItem.setRecordId(recordId);
                    resultItem.setContentHash(contentHash);
                    resultItem.setContentUrl(contentUrl);
                    resultItem.setSize(size);
                    successCount++;
                } catch (Exception e) {
                    resultItem.setStatus("FAILED");
                    resultItem.setErrorMessage(e.getMessage());
                    failedCount++;
                }

                results.add(resultItem);
            }

            batchStoreItemCounter.increment(successCount);
            log.info("Batch store completed: success={}, failed={}", successCount, failedCount);

            return BatchStoreResponse.builder()
                    .batchId(IdGenerator.generateBatchId())
                    .storageType(storageType)
                    .totalItems(items.size())
                    .successCount(successCount)
                    .failedCount(failedCount)
                    .results(results)
                    .build();
        });
    }

    @Timed(value = "storage.batch.retrieve", description = "Time taken to retrieve content in batch")
    public Mono<BatchRetrieveResponse> batchRetrieveContent(BatchRetrieveRequest request) {
        return Mono.fromCallable(() -> {
            List<String> recordIds = request.getRecordIds();
            List<BatchRetrieveResponse.RetrieveResultItem> results = new ArrayList<>();
            int successCount = 0;
            int failedCount = 0;

            log.info("Processing batch retrieve: {} records", recordIds.size());

            for (String recordId : recordIds) {
                BatchRetrieveResponse.RetrieveResultItem resultItem =
                        new BatchRetrieveResponse.RetrieveResultItem();
                resultItem.setRecordId(recordId);

                try {
                    RetrieveContentResponse response = retrieveContent(recordId).block();
                    if (response != null) {
                        resultItem.setStatus("SUCCESS");
                        resultItem.setStorageType(response.getStorageType());
                        resultItem.setContentHash(response.getContentHash());
                        resultItem.setContentUrl(response.getContentUrl());
                        resultItem.setContent(response.getContent());
                        resultItem.setPinStatus(response.getPinStatus());
                        resultItem.setSize(response.getSize());
                        resultItem.setCreatedAt(response.getCreatedAt());
                        resultItem.setMetadata(response.getMetadata());
                        successCount++;
                    } else {
                        resultItem.setStatus("FAILED");
                        resultItem.setErrorMessage("No data returned");
                        failedCount++;
                    }
                } catch (Exception e) {
                    resultItem.setStatus("FAILED");
                    resultItem.setErrorMessage(e.getMessage());
                    failedCount++;
                }

                results.add(resultItem);
            }

            log.info("Batch retrieve completed: success={}, failed={}", successCount, failedCount);

            return BatchRetrieveResponse.builder()
                    .batchId(IdGenerator.generateBatchId())
                    .totalItems(recordIds.size())
                    .successCount(successCount)
                    .failedCount(failedCount)
                    .results(results)
                    .build();
        });
    }

    @Retry(name = "storage", fallbackMethod = "retrieveContentFallback")
    public Mono<RetrieveContentResponse> retrieveContent(String recordId) {
        return Mono.fromCallable(() -> {
            StorageRecord record = getRecordInternal(recordId);
            if (record == null) {
                throw new BusinessException(404, "Storage record not found");
            }

            String content = retrieveContentFromProvider(record);
            log.info("Retrieved content from {} with hash: {}", record.getStorageType(), record.getContentHash());

            return RetrieveContentResponse.builder()
                    .recordId(record.getRecordId())
                    .storageType(record.getStorageType())
                    .contentHash(record.getContentHash())
                    .contentUrl(record.getContentUrl())
                    .content(content)
                    .pinStatus(record.getPinStatus())
                    .size(record.getSize())
                    .createdAt(record.getCreatedAt())
                    .metadata(record.getMetadata())
                    .build();
        });
    }

    @Transactional
    @Retry(name = "storage", fallbackMethod = "pinContentFallback")
    public Mono<StorageRecordResponse> pinContent(String recordId) {
        return Mono.fromCallable(() -> {
            StorageRecord record = getRecordInternal(recordId);
            if (record == null) {
                throw new BusinessException(404, "Storage record not found");
            }

            if (!STORAGE_IPFS.equals(record.getStorageType())) {
                throw new BusinessException(400, "Pin operation is only supported for IPFS storage");
            }

            record.setPinStatus(PIN_STATUS_PINNING);
            storageRecordMapper.updateById(record);

            Boolean pinned = ipfsProvider.pinContent(record.getContentHash()).block();
            if (Boolean.TRUE.equals(pinned)) {
                record.setPinStatus(PIN_STATUS_PINNED);
                record.setPinnedAt(Instant.now());
            }
            storageRecordMapper.updateById(record);

            log.info("Pinned content for record: {}", recordId);
            return toResponse(record);
        });
    }

    @Transactional
    public Mono<StorageRecordResponse> unpinContent(String recordId) {
        return Mono.fromCallable(() -> {
            StorageRecord record = getRecordInternal(recordId);
            if (record == null) {
                throw new BusinessException(404, "Storage record not found");
            }

            if (!STORAGE_IPFS.equals(record.getStorageType())) {
                throw new BusinessException(400, "Unpin operation is only supported for IPFS storage");
            }

            Boolean unpinned = ipfsProvider.unpinContent(record.getContentHash()).block();
            if (Boolean.TRUE.equals(unpinned)) {
                record.setPinStatus(PIN_STATUS_UNPINNED);
                record.setPinnedAt(null);
            }
            storageRecordMapper.updateById(record);

            log.info("Unpinned content for record: {}", recordId);
            return toResponse(record);
        });
    }

    @Cacheable(value = "storageRecords", key = "#recordId", unless = "#result == null")
    public Mono<StorageRecordResponse> getRecord(String recordId) {
        return Mono.fromCallable(() -> {
            StorageRecord record = getRecordInternal(recordId);
            if (record == null) {
                throw new BusinessException(404, "Storage record not found");
            }
            return toResponse(record);
        });
    }

    public Mono<StorageRecordResponse> getRecordByHash(String contentHash) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<StorageRecord> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(StorageRecord::getContentHash, contentHash)
                    .orderByDesc(StorageRecord::getCreatedAt)
                    .last("LIMIT 1");
            StorageRecord record = storageRecordMapper.selectOne(wrapper);
            if (record == null) {
                throw new BusinessException(404, "Storage record not found for hash: " + contentHash);
            }
            return toResponse(record);
        });
    }

    public Mono<List<StorageRecordResponse>> listRecords(String storageType, String pinStatus) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<StorageRecord> wrapper = new LambdaQueryWrapper<>();
            if (storageType != null) {
                wrapper.eq(StorageRecord::getStorageType, storageType.toUpperCase());
            }
            if (pinStatus != null) {
                wrapper.eq(StorageRecord::getPinStatus, pinStatus.toUpperCase());
            }
            wrapper.orderByDesc(StorageRecord::getCreatedAt);

            List<StorageRecord> records = storageRecordMapper.selectList(wrapper);
            return records.stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        });
    }

    private String storeContentToProvider(String storageType, String content) {
        if (STORAGE_IPFS.equals(storageType)) {
            return ipfsProvider.storeContent(content).block();
        } else {
            return arweaveProvider.storeContent(content).block();
        }
    }

    private String buildContentUrl(String storageType, String contentHash) {
        if (STORAGE_IPFS.equals(storageType)) {
            return ipfsProvider.getContentUrl(contentHash);
        } else {
            return arweaveProvider.getContentUrl(contentHash);
        }
    }

    private String retrieveContentFromProvider(StorageRecord record) {
        if (STORAGE_IPFS.equals(record.getStorageType())) {
            return ipfsProvider.retrieveContent(record.getContentHash()).block();
        } else {
            return arweaveProvider.retrieveContent(record.getContentHash()).block();
        }
    }

    private StorageRecord getRecordInternal(String recordId) {
        String cacheKey = "storage:record:" + recordId;
        Object cached = caffeineCache.getIfPresent(cacheKey);
        if (cached != null) {
            return (StorageRecord) cached;
        }
        StorageRecord record = storageRecordMapper.selectById(recordId);
        if (record != null) {
            caffeineCache.put(cacheKey, record);
        }
        return record;
    }

    private void validateStorageType(String storageType) {
        if (!STORAGE_IPFS.equals(storageType) && !STORAGE_ARWEAVE.equals(storageType)) {
            throw new BusinessException(400, "Unsupported storage type: " + storageType +
                    ". Supported types: IPFS, ARWEAVE");
        }
    }

    private StorageRecordResponse toResponse(StorageRecord record) {
        return StorageRecordResponse.builder()
                .recordId(record.getRecordId())
                .storageType(record.getStorageType())
                .contentHash(record.getContentHash())
                .contentUrl(record.getContentUrl())
                .pinStatus(record.getPinStatus())
                .size(record.getSize())
                .createdAt(record.getCreatedAt())
                .pinnedAt(record.getPinnedAt())
                .metadata(record.getMetadata())
                .build();
    }

    private Mono<StorageRecordResponse> storeContentFallback(StoreContentRequest request, Exception e) {
        log.error("Store content fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to store content after retries: " + e.getMessage());
    }

    private Mono<RetrieveContentResponse> retrieveContentFallback(String recordId, Exception e) {
        log.error("Retrieve content fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to retrieve content after retries: " + e.getMessage());
    }

    private Mono<StorageRecordResponse> pinContentFallback(String recordId, Exception e) {
        log.error("Pin content fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to pin content after retries: " + e.getMessage());
    }
}
