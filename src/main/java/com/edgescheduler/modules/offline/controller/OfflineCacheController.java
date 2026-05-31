package com.edgescheduler.modules.offline.controller;

import com.edgescheduler.common.Result;
import com.edgescheduler.modules.offline.domain.OfflineDataCache;
import com.edgescheduler.modules.offline.service.OfflineCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/offline")
@RequiredArgsConstructor
public class OfflineCacheController {

    private final OfflineCacheService offlineCacheService;

    @PostMapping("/cache")
    public Mono<Result<OfflineDataCache>> cacheData(
            @RequestParam String deviceId,
            @RequestParam String dataType,
            @RequestBody Map<String, Object> dataContent,
            @RequestParam(defaultValue = "1440") Integer ttlMinutes) {
        return offlineCacheService.cacheData(deviceId, dataType, dataContent, ttlMinutes)
                .map(Result::success);
    }

    @GetMapping("/cache")
    public Flux<Result<OfflineDataCache>> getCachedData(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String dataType,
            @RequestParam(defaultValue = "100") Integer limit) {
        return offlineCacheService.getCachedData(deviceId, dataType, limit)
                .map(Result::success);
    }

    @GetMapping("/cache/{cacheId}")
    public Mono<Result<OfflineDataCache>> getCacheById(@PathVariable String cacheId) {
        return offlineCacheService.getCacheById(cacheId)
                .map(Result::success);
    }

    @DeleteMapping("/cache/{cacheId}")
    public Mono<Result<Void>> deleteCache(@PathVariable String cacheId) {
        return offlineCacheService.deleteCache(cacheId)
                .then(Mono.just(Result.success()));
    }

    @PutMapping("/cache/{cacheId}/synced")
    public Mono<Result<OfflineDataCache>> markAsSynced(
            @PathVariable String cacheId,
            @RequestParam String syncResult) {
        return offlineCacheService.markAsSynced(cacheId, syncResult)
                .map(Result::success);
    }

    @PutMapping("/cache/{cacheId}/failed")
    public Mono<Result<OfflineDataCache>> markAsFailed(
            @PathVariable String cacheId,
            @RequestParam String errorDetail) {
        return offlineCacheService.markAsFailed(cacheId, errorDetail)
                .map(Result::success);
    }

    @PostMapping("/sync/trigger")
    public Mono<Result<Map<String, Object>>> triggerSync() {
        return offlineCacheService.triggerSync()
                .map(Result::success);
    }

    @GetMapping("/sync/pending")
    public Flux<Result<OfflineDataCache>> getPendingSync(
            @RequestParam(defaultValue = "100") Integer limit) {
        return offlineCacheService.getPendingSync(limit)
                .map(Result::success);
    }

    @GetMapping("/stats")
    public Mono<Result<Map<String, Object>>> getCacheStats() {
        return offlineCacheService.getCacheStats()
                .map(Result::success);
    }

    @PostMapping("/network/test")
    public Mono<Result<Boolean>> testNetworkConnectivity() {
        return offlineCacheService.testNetworkConnectivity()
                .map(Result::success);
    }

    @GetMapping("/network/status")
    public Mono<Result<Boolean>> isNetworkAvailable() {
        return offlineCacheService.isNetworkAvailable()
                .map(Result::success);
    }
}
