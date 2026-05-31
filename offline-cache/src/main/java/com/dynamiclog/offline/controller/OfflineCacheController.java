package com.dynamiclog.offline.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.entity.OfflineData;
import com.dynamiclog.offline.service.OfflineCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/offline")
@RequiredArgsConstructor
public class OfflineCacheController {

    private final OfflineCacheService offlineCacheService;

    @PostMapping("/data")
    public Mono<ApiResponse<OfflineData>> saveData(
            @RequestParam String dataType,
            @RequestParam String dataKey,
            @RequestBody String payload,
            @RequestParam(required = false) String sourceDevice) {
        return offlineCacheService.saveOfflineData(dataType, dataKey, payload, sourceDevice)
                .map(ApiResponse::success);
    }

    @GetMapping("/data/{dataKey}")
    public Mono<ApiResponse<OfflineData>> getData(@PathVariable String dataKey) {
        return offlineCacheService.getOfflineData(dataKey)
                .map(ApiResponse::success);
    }

    @GetMapping("/data/pending")
    public Mono<ApiResponse<List<OfflineData>>> getPendingSyncData() {
        return offlineCacheService.getPendingSyncData()
                .collectList()
                .map(ApiResponse::success);
    }

    @PostMapping("/sync")
    public Mono<ApiResponse<Void>> syncAllPending() {
        return offlineCacheService.syncAllPending()
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/status")
    public Mono<ApiResponse<Map<String, Object>>> getStatus() {
        return offlineCacheService.getPendingSyncCount()
                .map(count -> ApiResponse.success(Map.of(
                        "networkAvailable", offlineCacheService.isNetworkAvailable(),
                        "pendingSyncCount", count
                )));
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getDetailedStats() {
        return offlineCacheService.getDetailedStats()
                .map(ApiResponse::success);
    }

    @GetMapping("/stats/datatype/{dataType}")
    public Mono<ApiResponse<Map<String, Object>>> getDataTypeMetrics(@PathVariable String dataType) {
        return offlineCacheService.getDataTypeMetrics(dataType)
                .map(ApiResponse::success);
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<OfflineCacheService.SyncEvent> listenSyncEvents() {
        return offlineCacheService.listenSyncEvents();
    }
}
