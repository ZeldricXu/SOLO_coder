package com.scheduler.data.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Configuration
@EnableCaching
public class CacheManager {

    private final Map<String, com.github.benmanes.caffeine.cache.Cache<String, Object>> l1Caches = new ConcurrentHashMap<>();
    private final RedissonClient redissonClient;

    public CacheManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Bean
    public com.github.benmanes.caffeine.cache.Cache<String, Object> taskCache() {
        return createL1Cache("tasks", 10000, 10, TimeUnit.MINUTES);
    }

    @Bean
    public com.github.benmanes.caffeine.cache.Cache<String, Object> configCache() {
        return createL1Cache("configs", 5000, 5, TimeUnit.MINUTES);
    }

    private com.github.benmanes.caffeine.cache.Cache<String, Object> createL1Cache(String name, int maxSize, long ttl, TimeUnit unit) {
        com.github.benmanes.caffeine.cache.Cache<String, Object> cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl, unit)
                .recordStats()
                .build();
        l1Caches.put(name, cache);
        log.info("Created L1 cache: {} with maxSize={}, ttl={}{}", name, maxSize, ttl, unit);
        return cache;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String cacheName, String key, Function<String, T> loader) {
        com.github.benmanes.caffeine.cache.Cache<String, Object> l1Cache = l1Caches.get(cacheName);
        if (l1Cache != null) {
            T value = (T) l1Cache.getIfPresent(key);
            if (value != null) {
                log.debug("L1 cache hit for {}:{}", cacheName, key);
                return value;
            }
        }

        RMap<String, T> l2Cache = redissonClient.getMap(cacheName);
        T value = l2Cache.get(key);
        if (value != null) {
            log.debug("L2 cache hit for {}:{}", cacheName, key);
            if (l1Cache != null) {
                l1Cache.put(key, value);
            }
            return value;
        }

        log.debug("Cache miss for {}:{}, loading from source", cacheName, key);
        value = loader.apply(key);
        if (value != null) {
            if (l1Cache != null) {
                l1Cache.put(key, value);
            }
            l2Cache.fastPut(key, value, 10, TimeUnit.MINUTES);
        }
        return value;
    }

    public void invalidate(String cacheName, String key) {
        com.github.benmanes.caffeine.cache.Cache<String, Object> l1Cache = l1Caches.get(cacheName);
        if (l1Cache != null) {
            l1Cache.invalidate(key);
        }
        RMap<String, Object> l2Cache = redissonClient.getMap(cacheName);
        l2Cache.fastRemove(key);
        log.debug("Invalidated cache {}:{}", cacheName, key);
    }

    public void invalidateAll(String cacheName) {
        com.github.benmanes.caffeine.cache.Cache<String, Object> l1Cache = l1Caches.get(cacheName);
        if (l1Cache != null) {
            l1Cache.invalidateAll();
        }
        RMap<String, Object> l2Cache = redissonClient.getMap(cacheName);
        l2Cache.clear();
        log.debug("Invalidated all entries in cache {}", cacheName);
    }

    public CacheStats getStats(String cacheName) {
        com.github.benmanes.caffeine.cache.Cache<String, Object> cache = l1Caches.get(cacheName);
        return cache != null ? cache.stats() : null;
    }

    public void put(String cacheName, String key, Object value) {
        com.github.benmanes.caffeine.cache.Cache<String, Object> l1Cache = l1Caches.get(cacheName);
        if (l1Cache != null) {
            l1Cache.put(key, value);
        }
        RMap<String, Object> l2Cache = redissonClient.getMap(cacheName);
        l2Cache.fastPut(key, value, 10, TimeUnit.MINUTES);
    }
}
