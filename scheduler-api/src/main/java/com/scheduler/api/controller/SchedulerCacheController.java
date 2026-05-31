package com.scheduler.api.controller;

import com.scheduler.common.model.ApiResponse;
import com.scheduler.scheduler.cache.TaskCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduler/cache")
@RequiredArgsConstructor
public class SchedulerCacheController {

    private final TaskCacheService taskCacheService;

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getCacheStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = Map.of(
                    "l1Size", taskCacheService.getL1Size(),
                    "l2Size", taskCacheService.getL2Size(),
                    "l1HitRate", taskCacheService.getL1HitRate(),
                    "l1HitCount", taskCacheService.getL1Stats().hitCount(),
                    "l1MissCount", taskCacheService.getL1Stats().missCount(),
                    "l1EvictionCount", taskCacheService.getL1Stats().evictionCount(),
                    "warmed", taskCacheService.isWarmed(),
                    "cachedTaskIds", taskCacheService.getCachedTaskIds()
            );
            return ApiResponse.success(stats);
        });
    }

    @PostMapping("/warmup")
    public Mono<ApiResponse<String>> warmUpCache() {
        return Mono.fromCallable(() -> {
            taskCacheService.warmUp();
            return ApiResponse.success("Cache warm-up initiated, warmed: " + taskCacheService.isWarmed());
        });
    }

    @DeleteMapping("/{taskId}")
    public Mono<ApiResponse<String>> invalidateCache(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            taskCacheService.invalidate(taskId);
            return ApiResponse.success("Cache invalidated for task: " + taskId);
        });
    }

    @DeleteMapping("/all")
    public Mono<ApiResponse<String>> invalidateAllCache() {
        return Mono.fromCallable(() -> {
            taskCacheService.invalidateAll();
            return ApiResponse.success("All cache invalidated");
        });
    }

    @PostMapping("/cleanup")
    public Mono<ApiResponse<String>> cleanupExpired() {
        return Mono.fromCallable(() -> {
            taskCacheService.invalidateExpiredEntries();
            return ApiResponse.success("Expired cache entries cleaned up");
        });
    }
}
