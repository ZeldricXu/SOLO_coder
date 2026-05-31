package com.edgescheduler.cache.controller;

import com.edgescheduler.cache.dto.OfflineCacheDataDTO;
import com.edgescheduler.cache.entity.NetworkStatus;
import com.edgescheduler.cache.entity.OfflineCacheData;
import com.edgescheduler.cache.service.OfflineCacheService;
import com.edgescheduler.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cache")
@RequiredArgsConstructor
public class OfflineCacheController {

    private final OfflineCacheService cacheService;

    @PostMapping("/data")
    public Mono<ApiResponse<OfflineCacheDataDTO>> cacheData(@Valid @RequestBody OfflineCacheDataDTO cacheDTO) {
        return Mono.just(ApiResponse.created(cacheService.cacheData(cacheDTO)));
    }

    @GetMapping("/data/{cacheId}")
    public Mono<ApiResponse<OfflineCacheDataDTO>> getCacheData(@PathVariable String cacheId) {
        return Mono.just(ApiResponse.success(cacheService.getCacheData(cacheId)));
    }

    @GetMapping("/data/pending")
    public Mono<ApiResponse<List<OfflineCacheData>>> getPendingSyncData(
            @RequestParam(defaultValue = "100") int limit) {
        return Mono.just(ApiResponse.success(cacheService.getPendingSyncData(limit)));
    }

    @GetMapping("/data/device/{deviceKey}")
    public Mono<ApiResponse<List<OfflineCacheData>>> getDeviceCacheData(
            @PathVariable String deviceKey,
            @RequestParam(defaultValue = "20") int limit) {
        return Mono.just(ApiResponse.success(cacheService.getDeviceCacheData(deviceKey, limit)));
    }

    @PutMapping("/data/{cacheId}/syncing")
    public Mono<ApiResponse<OfflineCacheDataDTO>> markAsSyncing(@PathVariable String cacheId) {
        return Mono.just(ApiResponse.success(cacheService.markAsSyncing(cacheId)));
    }

    @PutMapping("/data/{cacheId}/synced")
    public Mono<ApiResponse<OfflineCacheDataDTO>> markAsSynced(@PathVariable String cacheId) {
        return Mono.just(ApiResponse.success(cacheService.markAsSynced(cacheId)));
    }

    @PutMapping("/data/{cacheId}/failed")
    public Mono<ApiResponse<OfflineCacheDataDTO>> markAsFailed(
            @PathVariable String cacheId,
            @RequestBody(required = false) Map<String, String> body) {
        String error = body != null ? body.get("error") : "Unknown error";
        return Mono.just(ApiResponse.success(cacheService.markAsFailed(cacheId, error)));
    }

    @PostMapping("/sync/batch")
    public Mono<ApiResponse<List<OfflineCacheData>>> syncBatchData(
            @RequestParam(defaultValue = "100") int batchSize) {
        return Mono.just(ApiResponse.success(cacheService.syncBatchData(batchSize)));
    }

    @PostMapping("/sync/trigger")
    public Mono<ApiResponse<Void>> triggerAutoSync() {
        cacheService.triggerAutoSync();
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/network/status")
    public Mono<ApiResponse<NetworkStatus>> updateNetworkStatus(@Valid @RequestBody NetworkStatus status) {
        return Mono.just(ApiResponse.success(cacheService.updateNetworkStatus(status)));
    }

    @GetMapping("/network/status")
    public Mono<ApiResponse<NetworkStatus>> getCurrentNetworkStatus() {
        return Mono.just(ApiResponse.success(cacheService.getCurrentNetworkStatus()));
    }

    @GetMapping("/network/online")
    public Mono<ApiResponse<Boolean>> isNetworkOnline() {
        return Mono.just(ApiResponse.success(cacheService.isNetworkOnline()));
    }

    @PostMapping("/data/expire")
    public Mono<ApiResponse<Integer>> expireOldData(
            @RequestParam(defaultValue = "72") int expireHours) {
        return Mono.just(ApiResponse.success(cacheService.expireOldData(expireHours)));
    }

    @GetMapping("/statistics")
    public Mono<ApiResponse<Map<String, Object>>> getCacheStatistics() {
        return Mono.just(ApiResponse.success(cacheService.getCacheStatistics()));
    }

    @DeleteMapping("/data/{cacheId}")
    public Mono<ApiResponse<Void>> deleteCacheData(@PathVariable String cacheId) {
        cacheService.deleteCacheData(cacheId);
        return Mono.just(ApiResponse.success(null));
    }

    @DeleteMapping("/data/synced")
    public Mono<ApiResponse<Void>> clearSyncedData() {
        cacheService.clearSyncedData();
        return Mono.just(ApiResponse.success(null));
    }
}
