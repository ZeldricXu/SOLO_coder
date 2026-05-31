package com.solocoder.platform.storage.service.impl;

import com.solocoder.platform.storage.cache.StorageCacheManager;
import com.solocoder.platform.storage.model.BatchOperationRequest;
import com.solocoder.platform.storage.model.BatchOperationResult;
import com.solocoder.platform.storage.service.BatchOperationService;
import com.solocoder.platform.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchOperationServiceImpl implements BatchOperationService {

    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;
    private final StorageCacheManager cacheManager;
    private final Map<String, byte[]> dataStore = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> metadataStore = new ConcurrentHashMap<>();

    @Override
    public BatchOperationResult executeBatch(BatchOperationRequest request) {
        String batchId = UUID.randomUUID().toString();
        LocalDateTime startTime = LocalDateTime.now();
        long startMs = System.currentTimeMillis();

        List<BatchOperationResult.OperationResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;

        List<BatchOperationRequest.BatchOperation> puts = new ArrayList<>();
        List<BatchOperationRequest.BatchOperation> gets = new ArrayList<>();
        List<BatchOperationRequest.BatchOperation> deletes = new ArrayList<>();

        for (BatchOperationRequest.BatchOperation op : request.getOperations()) {
            switch (op.getType()) {
                case PUT -> puts.add(op);
                case GET -> gets.add(op);
                case DELETE -> deletes.add(op);
            }
        }

        for (BatchOperationRequest.BatchOperation op : puts) {
            BatchOperationResult.OperationResult result = executePut(op);
            results.add(result);
            if (result.isSuccess()) successCount++;
            else failedCount++;
        }

        if (!gets.isEmpty()) {
            Map<String, BatchOperationResult.OperationResult> getResults = executeBatchGet(gets);
            for (BatchOperationRequest.BatchOperation op : gets) {
                BatchOperationResult.OperationResult result = getResults.getOrDefault(op.getKey(),
                        BatchOperationResult.OperationResult.builder()
                                .type(BatchOperationRequest.OperationType.GET)
                                .key(op.getKey())
                                .success(false)
                                .errorMessage("Not found")
                                .build());
                results.add(result);
                if (result.isSuccess()) successCount++;
                else failedCount++;
            }
        }

        if (!deletes.isEmpty()) {
            Map<String, Boolean> deleteResults = executeBatchDelete(deletes);
            for (BatchOperationRequest.BatchOperation op : deletes) {
                boolean success = deleteResults.getOrDefault(op.getKey(), false);
                results.add(BatchOperationResult.OperationResult.builder()
                        .type(BatchOperationRequest.OperationType.DELETE)
                        .key(op.getKey())
                        .success(success)
                        .errorMessage(success ? null : "Delete failed")
                        .build());
                if (success) successCount++;
                else failedCount++;
            }
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("Batch operation completed: batchId={}, total={}, success={}, failed={}, duration={}ms",
                batchId, request.getOperations().size(), successCount, failedCount, durationMs);

        return BatchOperationResult.builder()
                .batchId(batchId)
                .totalOperations(request.getOperations().size())
                .successCount(successCount)
                .failedCount(failedCount)
                .startTime(startTime)
                .endTime(LocalDateTime.now())
                .durationMs(durationMs)
                .results(results)
                .build();
    }

    @Override
    public BatchOperationRequest mergeRequests(List<BatchOperationRequest> requests) {
        List<BatchOperationRequest.BatchOperation> mergedOps = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();

        for (int i = requests.size() - 1; i >= 0; i--) {
            BatchOperationRequest request = requests.get(i);
            for (BatchOperationRequest.BatchOperation op : request.getOperations()) {
                String keyWithType = op.getType() + ":" + op.getKey();
                if (!processedKeys.contains(keyWithType)) {
                    processedKeys.add(keyWithType);
                    mergedOps.add(0, op);
                }
            }
        }

        log.info("Merged {} requests into {} operations", requests.size(), mergedOps.size());
        return BatchOperationRequest.builder()
                .operations(mergedOps)
                .build();
    }

    @Override
    public BatchOperationResult executeBatchWithMerge(BatchOperationRequest request) {
        return executeBatch(request);
    }

    private BatchOperationResult.OperationResult executePut(BatchOperationRequest.BatchOperation op) {
        try {
            StorageService.StorageItemResult itemResult = storageService.put(op.getKey(), op.getData(), op.getMetadata());
            return BatchOperationResult.OperationResult.builder()
                    .type(BatchOperationRequest.OperationType.PUT)
                    .key(op.getKey())
                    .success(true)
                    .size(itemResult.size())
                    .build();
        } catch (Exception e) {
            log.error("Batch PUT failed: key={}", op.getKey(), e);
            return BatchOperationResult.OperationResult.builder()
                    .type(BatchOperationRequest.OperationType.PUT)
                    .key(op.getKey())
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private Map<String, BatchOperationResult.OperationResult> executeBatchGet(List<BatchOperationRequest.BatchOperation> ops) {
        Map<String, BatchOperationResult.OperationResult> results = new HashMap<>();
        Set<String> keysToFetch = new HashSet<>();

        for (BatchOperationRequest.BatchOperation op : ops) {
            Optional<StorageService.StorageItemResult> cached = cacheManager.get(op.getKey());
            if (cached.isPresent()) {
                results.put(op.getKey(), BatchOperationResult.OperationResult.builder()
                        .type(BatchOperationRequest.OperationType.GET)
                        .key(op.getKey())
                        .success(true)
                        .data(cached.get().data())
                        .metadata(cached.get().metadata())
                        .size(cached.get().size())
                        .build());
            } else {
                keysToFetch.add(op.getKey());
            }
        }

        for (String key : keysToFetch) {
            Optional<StorageService.StorageItemResult> item = storageService.get(key);
            if (item.isPresent()) {
                if (cacheManager.isHotKey(key)) {
                    cacheManager.put(key, item.get());
                }
                results.put(key, BatchOperationResult.OperationResult.builder()
                        .type(BatchOperationRequest.OperationType.GET)
                        .key(key)
                        .success(true)
                        .data(item.get().data())
                        .metadata(item.get().metadata())
                        .size(item.get().size())
                        .build());
            } else {
                results.put(key, BatchOperationResult.OperationResult.builder()
                        .type(BatchOperationRequest.OperationType.GET)
                        .key(key)
                        .success(false)
                        .errorMessage("Key not found")
                        .build());
            }
        }

        return results;
    }

    private Map<String, Boolean> executeBatchDelete(List<BatchOperationRequest.BatchOperation> ops) {
        Map<String, Boolean> results = new HashMap<>();

        for (BatchOperationRequest.BatchOperation op : ops) {
            try {
                boolean deleted = storageService.delete(op.getKey());
                results.put(op.getKey(), deleted);
            } catch (Exception e) {
                log.error("Batch DELETE failed: key={}", op.getKey(), e);
                results.put(op.getKey(), false);
            }
        }

        return results;
    }
}
