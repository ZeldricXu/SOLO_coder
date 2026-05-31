package com.dynamiclog.dataaccess.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.enums.CacheStrategy;
import com.dynamiclog.dataaccess.service.MultiLevelCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cache")
@RequiredArgsConstructor
public class CacheController {

    private final MultiLevelCacheService cacheService;

    @PostMapping("/{cacheName}")
    public Mono<ApiResponse<Void>> createCache(
            @PathVariable String cacheName,
            @RequestParam(defaultValue = "CACHE_FIRST") CacheStrategy strategy,
            @RequestParam(defaultValue = "10000") long maxSize,
            @RequestParam(defaultValue = "3600") long ttlSeconds,
            @RequestParam(defaultValue = "1800") long ttiSeconds,
            @RequestParam(defaultValue = "true") boolean l2Enabled) {
        MultiLevelCacheService.CacheConfig config = new MultiLevelCacheService.CacheConfig();
        config.setStrategy(strategy);
        config.setMaxSize(maxSize);
        config.setTtlSeconds(ttlSeconds);
        config.setTtiSeconds(ttiSeconds);
        config.setL2Enabled(l2Enabled);
        cacheService.createCache(cacheName, config);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/{cacheName}/{key}")
    public Mono<ApiResponse<Object>> getValue(@PathVariable String cacheName, @PathVariable String key) {
        return cacheService.get(cacheName, key, k -> Mono.empty())
                .map(ApiResponse::success);
    }

    @PutMapping("/{cacheName}/{key}")
    public Mono<ApiResponse<Void>> putValue(
            @PathVariable String cacheName,
            @PathVariable String key,
            @RequestBody Object value) {
        return cacheService.put(cacheName, key, value)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @DeleteMapping("/{cacheName}/{key}")
    public Mono<ApiResponse<Void>> invalidateKey(@PathVariable String cacheName, @PathVariable String key) {
        return cacheService.invalidate(cacheName, key)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @DeleteMapping("/{cacheName}")
    public Mono<ApiResponse<Void>> invalidateAll(@PathVariable String cacheName) {
        return cacheService.invalidateAll(cacheName)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/{cacheName}/stats")
    public Mono<ApiResponse<Map<String, Object>>> getCacheStats(@PathVariable String cacheName) {
        return Mono.just(ApiResponse.success(cacheService.getCacheStats(cacheName)));
    }
}
