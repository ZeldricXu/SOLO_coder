package com.datapipeline.data.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CaffeineCacheBackend implements CacheBackend {

    private final Cache<String, Object> cache;

    public CaffeineCacheBackend(long maxSize, Duration defaultTtl) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(defaultTtl.toMillis(), TimeUnit.MILLISECONDS)
                .recordStats()
                .build();
        log.info("Caffeine cache initialized with maxSize={}, defaultTtl={}s", maxSize, defaultTtl.toSeconds());
    }

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        cache.put(key, value);
    }

    @Override
    public void invalidate(String key) {
        cache.invalidate(key);
    }

    @Override
    public void invalidatePattern(String pattern) {
        String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
        cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() {
        return cache.stats();
    }

}
