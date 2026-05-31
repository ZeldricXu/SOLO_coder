package com.datamasker.interfaces.controller;

import com.datamasker.application.service.ShardCacheService;
import com.datamasker.domain.shamir.cache.CacheStats;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.shamir.CacheStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shamir/cache")
@RequiredArgsConstructor
public class ShardCacheController {

    private final ShardCacheService shardCacheService;

    @GetMapping("/stats")
    public Result<CacheStatsResponse> getStats() {
        CacheStats stats = shardCacheService.getCacheStats();
        CacheStatsResponse response = new CacheStatsResponse();
        response.setHitRate(stats.getHitRate());
        response.setHitCount(stats.getHitCount());
        response.setMissCount(stats.getMissCount());
        response.setL1EntryCount(stats.getL1Size());
        response.setL2EntryCount(stats.getL2Size());
        response.setWarmupDuration(stats.getWarmupTimeMs());
        return Result.success(response);
    }

    @PostMapping("/warmup")
    public Result<Void> warmup() {
        shardCacheService.warmupCache();
        return Result.success(null);
    }

    @DeleteMapping("/invalidate/{secretId}")
    public Result<Void> invalidateSecret(@PathVariable String secretId) {
        shardCacheService.invalidateSecret(secretId);
        return Result.success(null);
    }

    @DeleteMapping("/invalidate/all")
    public Result<Void> invalidateAll() {
        shardCacheService.invalidateAll();
        return Result.success(null);
    }
}
