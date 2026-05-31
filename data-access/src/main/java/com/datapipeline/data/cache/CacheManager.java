package com.datapipeline.data.cache;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CacheManager {

    private final CacheBackend localCache;
    private final CacheBackend remoteCache;
    private final Map<String, CacheEntry<?>> entryMap;

    public CacheManager(CacheBackend localCache, CacheBackend remoteCache) {
        this.localCache = localCache;
        this.remoteCache = remoteCache;
        this.entryMap = new ConcurrentHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key) {
        CacheEntry<?> entry = entryMap.get(key);
        if (entry != null && entry.isExpired()) {
            invalidate(key);
            return Optional.empty();
        }

        Optional<Object> localValue = localCache.get(key);
        if (localValue.isPresent()) {
            log.debug("Cache hit (L1): {}", key);
            return Optional.of((T) localValue.get());
        }

        Optional<Object> remoteValue = remoteCache.get(key);
        if (remoteValue.isPresent()) {
            log.debug("Cache hit (L2): {}", key);
            localCache.put(key, remoteValue.get(), entry != null ? entry.ttl() : Duration.ofMinutes(5));
            return Optional.of((T) remoteValue.get());
        }

        log.debug("Cache miss: {}", key);
        return Optional.empty();
    }

    public <T> void put(String key, T value, Duration ttl) {
        CacheEntry<T> entry = new CacheEntry<>(value, ttl);
        entryMap.put(key, entry);
        localCache.put(key, value, ttl);
        remoteCache.put(key, value, ttl);
        log.debug("Cache put: {}, ttl={}s", key, ttl.toSeconds());
    }

    public void invalidate(String key) {
        entryMap.remove(key);
        localCache.invalidate(key);
        remoteCache.invalidate(key);
        log.debug("Cache invalidated: {}", key);
    }

    public void invalidatePattern(String pattern) {
        entryMap.keySet().removeIf(k -> matchesPattern(k, pattern));
        localCache.invalidatePattern(pattern);
        remoteCache.invalidatePattern(pattern);
        log.debug("Cache pattern invalidated: {}", pattern);
    }

    public void invalidateAll() {
        entryMap.clear();
        localCache.invalidateAll();
        remoteCache.invalidateAll();
        log.debug("All caches invalidated");
    }

    public boolean exists(String key) {
        Optional<?> cached = get(key);
        return cached.isPresent();
    }

    private boolean matchesPattern(String key, String pattern) {
        if (pattern.endsWith("*")) {
            return key.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return key.equals(pattern);
    }

    public record CacheEntry<T>(T value, Duration ttl, long createdAt) {
        public CacheEntry(T value, Duration ttl) {
            this(value, ttl, System.currentTimeMillis());
        }

        public boolean isExpired() {
            long elapsed = System.currentTimeMillis() - createdAt;
            return elapsed > ttl.toMillis();
        }
    }

}
