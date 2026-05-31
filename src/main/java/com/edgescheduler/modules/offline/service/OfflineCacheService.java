package com.edgescheduler.modules.offline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.util.IdGenerator;
import com.edgescheduler.modules.offline.domain.OfflineDataCache;
import com.edgescheduler.modules.offline.mapper.OfflineDataCacheMapper;
import com.alibaba.fastjson2.JSON;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineCacheService {

    private final OfflineDataCacheMapper offlineDataCacheMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean networkAvailable = new AtomicBoolean(true);
    private final Map<String, List<Map<String, Object>>> pendingSyncQueue = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 100000;
    private static final int MAX_SYNC_RETRIES = 5;

    @Transactional(rollbackFor = Exception.class)
    public Mono<OfflineDataCache> cacheData(String deviceId, String dataType,
                                             Map<String, Object> dataContent, Integer ttlMinutes) {
        String dataStr = JSON.toJSONString(dataContent);
        long dataSize = dataStr.getBytes().length;

        OfflineDataCache cache = new OfflineDataCache();
        cache.setCacheId(IdGenerator.generateId("cache"));
        cache.setDeviceId(deviceId);
        cache.setDataType(dataType);
        cache.setDataContent(dataContent);
        cache.setDataSize(dataSize);
        cache.setCacheTime(LocalDateTime.now());
        cache.setSyncStatus(networkAvailable.get() ? "PENDING" : "OFFLINE");
        cache.setSyncAttempts(0);
        cache.setExpireTime(LocalDateTime.now().plusMinutes(ttlMinutes != null ? ttlMinutes : 1440));
        cache.setPriority(5);

        offlineDataCacheMapper.insert(cache);

        if (networkAvailable.get()) {
            queueForSync(cache);
        }

        updateMetrics("data_cached");
        return Mono.just(cache);
    }

    private void queueForSync(OfflineDataCache cache) {
        String key = cache.getDeviceId() + ":" + cache.getDataType();
        pendingSyncQueue.computeIfAbsent(key, k -> new ArrayList<>());
        pendingSyncQueue.get(key).add(cache.getDataContent());
    }

    @Scheduled(fixedDelay = 10000)
    public void syncPendingData() {
        if (!networkAvailable.get()) {
            log.info("Network unavailable, skipping sync");
            return;
        }

        List<OfflineDataCache> pendingCaches = offlineDataCacheMapper.selectList(
                new LambdaQueryWrapper<OfflineDataCache>()
                        .in(OfflineDataCache::getSyncStatus, "PENDING", "FAILED", "OFFLINE")
                        .lt(OfflineDataCache::getSyncAttempts, MAX_SYNC_RETRIES)
                        .orderByDesc(OfflineDataCache::getPriority)
                        .orderByAsc(OfflineDataCache::getCacheTime)
                        .last("LIMIT 100"));

        for (OfflineDataCache cache : pendingCaches) {
            syncCacheData(cache)
                    .doOnSuccess(result -> {
                        cache.setSyncStatus("SYNCED");
                        cache.setSyncTime(LocalDateTime.now());
                        cache.setSyncResult("success");
                        offlineDataCacheMapper.updateById(cache);
                        updateMetrics("data_synced");
                    })
                    .doOnError(error -> {
                        cache.setSyncAttempts(cache.getSyncAttempts() + 1);
                        cache.setLastSyncAttempt(LocalDateTime.now());
                        if (cache.getSyncAttempts() >= MAX_SYNC_RETRIES) {
                            cache.setSyncStatus("FAILED");
                            cache.setErrorDetail(error.getMessage());
                            updateMetrics("sync_failed");
                        } else {
                            cache.setSyncStatus("PENDING");
                        }
                        offlineDataCacheMapper.updateById(cache);
                        log.warn("Sync failed for cache: {}, attempt: {}",
                                cache.getCacheId(), cache.getSyncAttempts(), error);
                    })
                    .subscribe();
        }
    }

    private Mono<Boolean> syncCacheData(OfflineDataCache cache) {
        return Mono.fromCallable(() -> {
            try {
                String syncKey = "sync:" + cache.getCacheId();
                redisTemplate.opsForValue().set(syncKey, cache.getDataContent(),
                        Duration.ofMinutes(30)).block();

                redisTemplate.convertAndSend("cache:sync:completed", cache.getCacheId()).block();
                return true;
            } catch (Exception e) {
                throw new RuntimeException("Sync failed", e);
            }
        });
    }

    @Scheduled(fixedRate = 10000)
    public void checkNetworkStatus() {
        try {
            Boolean pingResult = redisTemplate.hasKey("network:healthcheck").block();
            networkAvailable.set(true);
            if (pingResult == null || !pingResult) {
                redisTemplate.opsForValue().set("network:healthcheck", "ok", Duration.ofSeconds(30)).subscribe();
            }
        } catch (Exception e) {
            if (networkAvailable.get()) {
                log.warn("Network became unavailable");
                markAllAsOffline();
            }
            networkAvailable.set(false);
        }
    }

    private void markAllAsOffline() {
        List<OfflineDataCache> pendingCaches = offlineDataCacheMapper.selectList(
                new LambdaQueryWrapper<OfflineDataCache>()
                        .eq(OfflineDataCache::getSyncStatus, "PENDING"));

        for (OfflineDataCache cache : pendingCaches) {
            cache.setSyncStatus("OFFLINE");
            offlineDataCacheMapper.updateById(cache);
        }

        updateMetrics("network_offline");
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<Map<String, Object>> triggerSync() {
        Map<String, Object> result = new HashMap<>();
        if (!networkAvailable.get()) {
            result.put("success", false);
            result.put("message", "Network unavailable");
            return Mono.just(result);
        }

        List<OfflineDataCache> offlineCaches = offlineDataCacheMapper.selectList(
                new LambdaQueryWrapper<OfflineDataCache>()
                        .eq(OfflineDataCache::getSyncStatus, "OFFLINE"));

        for (OfflineDataCache cache : offlineCaches) {
            cache.setSyncStatus("PENDING");
            cache.setSyncAttempts(0);
            offlineDataCacheMapper.updateById(cache);
        }

        result.put("success", true);
        result.put("triggeredCount", offlineCaches.size());
        result.put("message", "Sync triggered for " + offlineCaches.size() + " entries");
        updateMetrics("network_restored");
        return Mono.just(result);
    }

    public Flux<OfflineDataCache> getCachedData(String deviceId, String dataType, Integer limit) {
        List<OfflineDataCache> caches = offlineDataCacheMapper.selectList(
                new LambdaQueryWrapper<OfflineDataCache>()
                        .eq(deviceId != null, OfflineDataCache::getDeviceId, deviceId)
                        .eq(dataType != null, OfflineDataCache::getDataType, dataType)
                        .orderByDesc(OfflineDataCache::getCacheTime)
                        .last("LIMIT " + (limit != null ? limit : 100)));
        return Flux.fromIterable(caches);
    }

    public Mono<OfflineDataCache> getCacheById(String cacheId) {
        OfflineDataCache cache = offlineDataCacheMapper.selectOne(
                new LambdaQueryWrapper<OfflineDataCache>().eq(OfflineDataCache::getCacheId, cacheId));
        if (cache == null) {
            return Mono.error(new BusinessException("缓存记录不存在"));
        }
        return Mono.just(cache);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deleteCache(String cacheId) {
        OfflineDataCache cache = offlineDataCacheMapper.selectOne(
                new LambdaQueryWrapper<OfflineDataCache>().eq(OfflineDataCache::getCacheId, cacheId));
        if (cache != null) {
            offlineDataCacheMapper.deleteById(cache.getId());
        }
        return Mono.empty();
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<OfflineDataCache> markAsSynced(String cacheId, String syncResult) {
        OfflineDataCache cache = offlineDataCacheMapper.selectOne(
                new LambdaQueryWrapper<OfflineDataCache>().eq(OfflineDataCache::getCacheId, cacheId));
        if (cache == null) {
            return Mono.error(new BusinessException("缓存记录不存在"));
        }
        cache.setSyncStatus("SYNCED");
        cache.setSyncTime(LocalDateTime.now());
        cache.setSyncResult(syncResult);
        offlineDataCacheMapper.updateById(cache);
        updateMetrics("data_synced");
        return Mono.just(cache);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<OfflineDataCache> markAsFailed(String cacheId, String errorDetail) {
        OfflineDataCache cache = offlineDataCacheMapper.selectOne(
                new LambdaQueryWrapper<OfflineDataCache>().eq(OfflineDataCache::getCacheId, cacheId));
        if (cache == null) {
            return Mono.error(new BusinessException("缓存记录不存在"));
        }
        cache.setSyncAttempts(cache.getSyncAttempts() + 1);
        cache.setLastSyncAttempt(LocalDateTime.now());
        if (cache.getSyncAttempts() >= MAX_SYNC_RETRIES) {
            cache.setSyncStatus("FAILED");
        }
        cache.setErrorDetail(errorDetail);
        offlineDataCacheMapper.updateById(cache);
        updateMetrics("sync_failed");
        return Mono.just(cache);
    }

    public Flux<OfflineDataCache> getPendingSync(Integer limit) {
        List<OfflineDataCache> caches = offlineDataCacheMapper.selectList(
                new LambdaQueryWrapper<OfflineDataCache>()
                        .in(OfflineDataCache::getSyncStatus, "PENDING", "OFFLINE")
                        .orderByDesc(OfflineDataCache::getPriority)
                        .orderByAsc(OfflineDataCache::getCacheTime)
                        .last("LIMIT " + (limit != null ? limit : 100)));
        return Flux.fromIterable(caches);
    }

    public Mono<Map<String, Object>> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("networkAvailable", networkAvailable.get());

        long totalCache = offlineDataCacheMapper.selectCount(null);
        stats.put("totalCacheEntries", totalCache);

        long pendingSync = offlineDataCacheMapper.selectCount(
                new LambdaQueryWrapper<OfflineDataCache>()
                        .in(OfflineDataCache::getSyncStatus, "PENDING", "OFFLINE"));
        stats.put("pendingSyncEntries", pendingSync);

        long synced = offlineDataCacheMapper.selectCount(
                new LambdaQueryWrapper<OfflineDataCache>()
                        .eq(OfflineDataCache::getSyncStatus, "SYNCED"));
        stats.put("syncedEntries", synced);

        long failed = offlineDataCacheMapper.selectCount(
                new LambdaQueryWrapper<OfflineDataCache>()
                        .eq(OfflineDataCache::getSyncStatus, "FAILED"));
        stats.put("failedEntries", failed);

        return Mono.just(stats);
    }

    public Mono<Boolean> testNetworkConnectivity() {
        return Mono.fromCallable(() -> {
            try {
                redisTemplate.hasKey("network:healthcheck").block();
                networkAvailable.set(true);
                return true;
            } catch (Exception e) {
                networkAvailable.set(false);
                return false;
            }
        });
    }

    public Mono<Boolean> isNetworkAvailable() {
        return Mono.just(networkAvailable.get());
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredCache() {
        log.info("Starting cleanup of expired cache data");
        int deletedCount = offlineDataCacheMapper.delete(
                new LambdaQueryWrapper<OfflineDataCache>()
                        .lt(OfflineDataCache::getExpireTime, LocalDateTime.now())
                        .or()
                        .eq(OfflineDataCache::getSyncStatus, "SYNCED")
                        .lt(OfflineDataCache::getSyncTime, LocalDateTime.now().minusDays(1)));
        log.info("Cleaned up {} expired cache entries", deletedCount);
        updateMetrics("cache_cleanup");
    }

    private void updateMetrics(String action) {
        meterRegistry.counter("edge_scheduler_cache_operations_total", "action", action).increment();
    }
}
