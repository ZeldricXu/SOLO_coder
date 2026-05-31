package com.solocoder.platform.storage.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.solocoder.platform.storage.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class StorageCacheManager {

    private final Cache<String, StorageService.StorageItemResult> readCache;
    private final Map<String, Long> hotKeys = new ConcurrentHashMap<>();
    private final int hotKeyThreshold;

    public StorageCacheManager() {
        this(10000, Duration.ofMinutes(10), 5);
    }

    public StorageCacheManager(long maxSize, Duration expireAfterWrite, int hotKeyThreshold) {
        this.hotKeyThreshold = hotKeyThreshold;
        this.readCache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireAfterWrite)
                .recordStats()
                .build();
        log.info("Storage cache initialized: maxSize={}, expireAfterWrite={}, hotKeyThreshold={}", maxSize, expireAfterWrite, hotKeyThreshold);
    }

    public Optional<StorageService.StorageItemResult> get(String key) {
        StorageService.StorageItemResult cached = readCache.getIfPresent(key);
        if (cached != null) {
            hotKeys.merge(key, 1L, Long::sum);
            log.debug("Storage cache hit: key={}", key);
            return Optional.of(cached);
        }
        log.debug("Storage cache miss: key={}", key);
        return Optional.empty();
    }

    public void put(String key, StorageService.StorageItemResult item) {
        readCache.put(key, item);
        hotKeys.merge(key, 1L, Long::sum);
        log.debug("Storage cache put: key={}", key);
    }

    public void invalidate(String key) {
        readCache.invalidate(key);
        hotKeys.remove(key);
        log.debug("Storage cache invalidated: key={}", key);
    }

    public void invalidateAll() {
        readCache.invalidateAll();
        hotKeys.clear();
        log.debug("Storage cache cleared all");
    }

    public boolean isHotKey(String key) {
        return hotKeys.getOrDefault(key, 0L) >= hotKeyThreshold;
    }

    public void warmup(String key, StorageService.StorageItemResult item) {
        readCache.put(key, item);
        log.info("Storage cache warmed up: key={}", key);
    }

    public Map<String, StorageService.StorageItemResult> getAllCached() {
        return readCache.asMap();
    }

    public com.github.benmanes.caffeine.cache.CacheStats stats() {
        return readCache.stats();
    }
}
