package com.solocoder.platform.storage.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.storage.model.*;
import com.solocoder.platform.storage.service.BatchOperationService;
import com.solocoder.platform.storage.service.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;
    private final BatchOperationService batchOperationService;
    private final com.solocoder.platform.storage.cache.StorageCacheManager cacheManager;

    @PutMapping("/{key}")
    public ApiResponse<StorageService.StorageItemResult> put(@PathVariable String key,
                                                             @RequestBody StorageItem item) {
        return ApiResponse.success(storageService.put(key, item.getData(), item.getMetadata()));
    }

    @GetMapping("/{key}")
    public ApiResponse<StorageService.StorageItemResult> get(@PathVariable String key) {
        return storageService.get(key)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Item not found: " + key));
    }

    @DeleteMapping("/{key}")
    public ApiResponse<Void> delete(@PathVariable String key) {
        storageService.delete(key);
        return ApiResponse.success();
    }

    @PostMapping("/backup")
    public ApiResponse<BackupRecord> createBackup(@RequestParam String sourcePath,
                                                  @RequestParam String targetPath) {
        return ApiResponse.success(storageService.createBackup(sourcePath, targetPath));
    }

    @PostMapping("/recover")
    public ApiResponse<RecoveryRecord> recover(@RequestParam String backupId,
                                               @RequestParam String targetPath) {
        return ApiResponse.success(storageService.recover(backupId, targetPath));
    }

    @GetMapping("/backups")
    public ApiResponse<List<BackupRecord>> listBackups() {
        return ApiResponse.success(storageService.listBackups());
    }

    @GetMapping("/backups/{backupId}")
    public ApiResponse<BackupRecord> getBackup(@PathVariable String backupId) {
        return storageService.getBackup(backupId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Backup not found: " + backupId));
    }

    @PostMapping("/batch")
    public ApiResponse<BatchOperationResult> executeBatch(@Valid @RequestBody BatchOperationRequest request) {
        return ApiResponse.success(batchOperationService.executeBatch(request));
    }

    @PostMapping("/batch/merge")
    public ApiResponse<BatchOperationRequest> mergeAndExecute(@RequestBody List<BatchOperationRequest> requests) {
        BatchOperationRequest merged = batchOperationService.mergeRequests(requests);
        return ApiResponse.success(merged);
    }

    @GetMapping("/cache/stats")
    public ApiResponse<java.util.Map<String, Object>> getCacheStats() {
        com.github.benmanes.caffeine.cache.CacheStats stats = cacheManager.stats();
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("hitCount", stats.hitCount());
        result.put("missCount", stats.missCount());
        result.put("hitRate", String.format("%.2f%%", stats.hitRate() * 100));
        result.put("evictionCount", stats.evictionCount());
        result.put("cachedItemCount", cacheManager.getAllCached().size());
        return ApiResponse.success(result);
    }

    @PostMapping("/cache/warmup")
    public ApiResponse<Void> warmupCache(@RequestBody List<String> keys) {
        for (String key : keys) {
            storageService.get(key).ifPresent(item -> cacheManager.warmup(key, item));
        }
        return ApiResponse.success();
    }

    @DeleteMapping("/cache")
    public ApiResponse<Void> clearCache() {
        cacheManager.invalidateAll();
        return ApiResponse.success();
    }
}
