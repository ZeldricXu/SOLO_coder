package com.didauth.common.cache;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.Serializable;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class MultiLevelCache {

    private final CacheManager caffeineCacheManager;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    private final Map<String, CacheStats> stats = new ConcurrentHashMap<>();

    @Data
    public static class CacheStats implements Serializable {
        private long hits;
        private long misses;
        private long puts;
        private long evictions;

        public double hitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
    }

    @Data
    @RequiredArgsConstructor
    public static class CacheEntry<T> implements Serializable {
        private final T value;
        private final long createdAt;
        private final long ttlMs;

        public boolean isExpired() {
            return ttlMs > 0 && (System.currentTimeMillis() - createdAt) > ttlMs;
        }
    }

    public <T> Mono<T> get(String cacheName, String key, Class<T> type,
                           Function<String, Mono<T>> loader, Duration ttl) {
        return get(cacheName, key, type, loader, ttl, true);
    }

    @SuppressWarnings("unchecked")
    public <T> Mono<T> get(String cacheName, String key, Class<T> type,
                           Function<String, Mono<T>> loader, Duration ttl, boolean cacheNulls) {
        CacheStats cacheStats = stats.computeIfAbsent(cacheName, k -> new CacheStats());
        String fullKey = cacheName + ":" + key;

        Cache l1Cache = caffeineCacheManager.getCache(cacheName);
        if (l1Cache != null) {
            Cache.ValueWrapper wrapper = l1Cache.get(fullKey);
            if (wrapper != null) {
                CacheEntry<T> entry = (CacheEntry<T>) wrapper.get();
                if (entry != null && !entry.isExpired()) {
                    cacheStats.setHits(cacheStats.getHits() + 1);
                    log.debug("L1 cache hit: {}", fullKey);
                    return Mono.just(entry.getValue());
                }
                l1Cache.evict(fullKey);
            }
        }

        cacheStats.setMisses(cacheStats.getMisses() + 1);
        log.debug("L1 cache miss, trying L2: {}", fullKey);

        return redisTemplate.opsForValue().get(fullKey)
                .map(value -> {
                    try {
                        T typedValue = type.cast(value);
                        CacheEntry<T> entry = new CacheEntry<>(typedValue, System.currentTimeMillis(), ttl.toMillis());
                        if (l1Cache != null) {
                            l1Cache.put(fullKey, entry);
                        }
                        cacheStats.setHits(cacheStats.getHits() + 1);
                        log.debug("L2 cache hit: {}", fullKey);
                        return typedValue;
                    } catch (ClassCastException e) {
                        log.warn("L2 cache value type mismatch for key {}", fullKey);
                        return null;
                    }
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("L2 cache miss, loading from source: {}", fullKey);
                    return loader.apply(key)
                            .flatMap(value -> {
                                if (value != null || cacheNulls) {
                                    CacheEntry<T> entry = new CacheEntry<>(value, System.currentTimeMillis(), ttl.toMillis());
                                    if (l1Cache != null) {
                                        l1Cache.put(fullKey, entry);
                                    }
                                    redisTemplate.opsForValue().set(fullKey, value, ttl).subscribe();
                                    cacheStats.setPuts(cacheStats.getPuts() + 1);
                                }
                                return Mono.justOrEmpty(value);
                            });
                }));
    }

    public Mono<Void> evict(String cacheName, String key) {
        String fullKey = cacheName + ":" + key;
        Cache l1Cache = caffeineCacheManager.getCache(cacheName);
        if (l1Cache != null) {
            l1Cache.evict(fullKey);
        }
        CacheStats cacheStats = stats.get(cacheName);
        if (cacheStats != null) {
            cacheStats.setEvictions(cacheStats.getEvictions() + 1);
        }
        return redisTemplate.delete(fullKey).then();
    }

    public Mono<Void> clear(String cacheName) {
        Cache l1Cache = caffeineCacheManager.getCache(cacheName);
        if (l1Cache != null) {
            l1Cache.clear();
        }
        return redisTemplate.delete(redisTemplate.keys(cacheName + ":*"))
                .then();
    }

    public Mono<Boolean> warmUp(String cacheName, Map<String, Object> entries, Duration ttl) {
        Cache l1Cache = caffeineCacheManager.getCache(cacheName);
        entries.forEach((key, value) -> {
            String fullKey = cacheName + ":" + key;
            if (l1Cache != null) {
                l1Cache.put(fullKey, new CacheEntry<>(value, System.currentTimeMillis(), ttl.toMillis()));
            }
            redisTemplate.opsForValue().set(fullKey, value, ttl).subscribe();
        });
        return Mono.just(true);
    }

    public CacheStats getStats(String cacheName) {
        return stats.getOrDefault(cacheName, new CacheStats());
    }

    public Map<String, CacheStats> getAllStats() {
        return new ConcurrentHashMap<>(stats);
    }

    public void resetStats(String cacheName) {
        stats.remove(cacheName);
    }
}
