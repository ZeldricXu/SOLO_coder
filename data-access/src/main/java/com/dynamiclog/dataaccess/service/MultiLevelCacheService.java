package com.dynamiclog.dataaccess.service;

import com.dynamiclog.common.enums.CacheStrategy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiLevelCacheService {

    private final RedissonClient redissonClient;

    private final Map<String, Cache<String, Object>> l1Caches = new ConcurrentHashMap<>();
    private final Map<String, CacheConfig> cacheConfigs = new ConcurrentHashMap<>();

    public void createCache(String cacheName, CacheConfig config) {
        Cache<String, Object> l1Cache = Caffeine.newBuilder()
                .maximumSize(config.getMaxSize())
                .expireAfterWrite(config.getTtlSeconds(), TimeUnit.SECONDS)
                .expireAfterAccess(config.getTtiSeconds(), TimeUnit.SECONDS)
                .recordStats()
                .build();

        l1Caches.put(cacheName, l1Cache);
        cacheConfigs.put(cacheName, config);
        log.info("Cache created: name={}, strategy={}", cacheName, config.getStrategy());
    }

    public Mono<Object> get(String cacheName, String key, Function<String, Mono<Object>> loader) {
        CacheConfig config = cacheConfigs.get(cacheName);
        if (config == null) {
            return loader.apply(key);
        }

        return switch (config.getStrategy()) {
            case CACHE_FIRST -> getCacheFirst(cacheName, key, loader, config);
            case CACHE_ONLY -> getCacheOnly(cacheName, key);
            case CACHE_ASIDE -> getCacheAside(cacheName, key, loader, config);
            default -> loader.apply(key);
        };
    }

    public Mono<Void> put(String cacheName, String key, Object value) {
        CacheConfig config = cacheConfigs.get(cacheName);
        if (config == null) {
            return Mono.empty();
        }

        return switch (config.getStrategy()) {
            case WRITE_THROUGH -> putWriteThrough(cacheName, key, value, config);
            case WRITE_BEHIND -> putWriteBehind(cacheName, key, value, config);
            default -> putCacheOnly(cacheName, key, value, config);
        };
    }

    public Mono<Void> invalidate(String cacheName, String key) {
        return Mono.fromRunnable(() -> {
            Cache<String, Object> l1Cache = l1Caches.get(cacheName);
            if (l1Cache != null) {
                l1Cache.invalidate(key);
            }
            if (redissonClient != null) {
                RMap<String, Object> l2Cache = redissonClient.getMap(cacheName);
                l2Cache.remove(key);
            }
            log.debug("Cache invalidated: cache={}, key={}", cacheName, key);
        });
    }

    public Mono<Void> invalidateAll(String cacheName) {
        return Mono.fromRunnable(() -> {
            Cache<String, Object> l1Cache = l1Caches.get(cacheName);
            if (l1Cache != null) {
                l1Cache.invalidateAll();
            }
            if (redissonClient != null) {
                RMap<String, Object> l2Cache = redissonClient.getMap(cacheName);
                l2Cache.clear();
            }
            log.info("Cache cleared: {}", cacheName);
        });
    }

    public Map<String, Object> getCacheStats(String cacheName) {
        Cache<String, Object> l1Cache = l1Caches.get(cacheName);
        CacheConfig config = cacheConfigs.get(cacheName);

        if (l1Cache == null) {
            return Map.of("error", "Cache not found: " + cacheName);
        }

        CacheStats stats = l1Cache.stats();
        return Map.of(
                "name", cacheName,
                "strategy", config != null ? config.getStrategy().name() : "UNKNOWN",
                "size", l1Cache.estimatedSize(),
                "hitCount", stats.hitCount(),
                "missCount", stats.missCount(),
                "hitRate", stats.hitRate(),
                "evictionCount", stats.evictionCount(),
                "averageLoadPenalty", stats.averageLoadPenalty()
        );
    }

    private Mono<Object> getCacheFirst(String cacheName, String key, Function<String, Mono<Object>> loader, CacheConfig config) {
        Cache<String, Object> l1Cache = l1Caches.get(cacheName);
        Object l1Value = l1Cache != null ? l1Cache.getIfPresent(key) : null;

        if (l1Value != null) {
            log.debug("L1 cache hit: cache={}, key={}", cacheName, key);
            return Mono.just(l1Value);
        }

        if (redissonClient != null) {
            try {
                RMap<String, Object> l2Cache = redissonClient.getMap(cacheName);
                Object l2Value = l2Cache.get(key);
                if (l2Value != null) {
                    log.debug("L2 cache hit: cache={}, key={}", cacheName, key);
                    if (l1Cache != null) {
                        l1Cache.put(key, l2Value);
                    }
                    return Mono.just(l2Value);
                }
            } catch (Exception e) {
                log.warn("L2 cache access failed: {}", e.getMessage());
            }
        }

        return loader.apply(key)
                .doOnNext(value -> {
                    if (l1Cache != null) {
                        l1Cache.put(key, value);
                    }
                    if (redissonClient != null && config.isL2Enabled()) {
                        RMap<String, Object> l2Cache = redissonClient.getMap(cacheName);
                        l2Cache.put(key, value, config.getTtlSeconds(), TimeUnit.SECONDS);
                    }
                });
    }

    private Mono<Object> getCacheOnly(String cacheName, String key) {
        Cache<String, Object> l1Cache = l1Caches.get(cacheName);
        Object value = l1Cache != null ? l1Cache.getIfPresent(key) : null;
        return value != null ? Mono.just(value) : Mono.empty();
    }

    private Mono<Object> getCacheAside(String cacheName, String key, Function<String, Mono<Object>> loader, CacheConfig config) {
        Cache<String, Object> l1Cache = l1Caches.get(cacheName);
        Object l1Value = l1Cache != null ? l1Cache.getIfPresent(key) : null;

        if (l1Value != null) {
            return Mono.just(l1Value);
        }

        return loader.apply(key)
                .doOnNext(value -> {
                    if (l1Cache != null) {
                        l1Cache.put(key, value);
                    }
                });
    }

    private Mono<Void> putWriteThrough(String cacheName, String key, Object value, CacheConfig config) {
        return Mono.fromRunnable(() -> {
            Cache<String, Object> l1Cache = l1Caches.get(cacheName);
            if (l1Cache != null) {
                l1Cache.put(key, value);
            }
            if (redissonClient != null && config.isL2Enabled()) {
                RMap<String, Object> l2Cache = redissonClient.getMap(cacheName);
                l2Cache.put(key, value, config.getTtlSeconds(), TimeUnit.SECONDS);
            }
        });
    }

    private Mono<Void> putWriteBehind(String cacheName, String key, Object value, CacheConfig config) {
        return Mono.fromRunnable(() -> {
            Cache<String, Object> l1Cache = l1Caches.get(cacheName);
            if (l1Cache != null) {
                l1Cache.put(key, value);
            }
        }).doOnNext(v -> Mono.fromRunnable(() -> {
            if (redissonClient != null && config.isL2Enabled()) {
                RMap<String, Object> l2Cache = redissonClient.getMap(cacheName);
                l2Cache.put(key, value, config.getTtlSeconds(), TimeUnit.SECONDS);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe()).then();
    }

    private Mono<Void> putCacheOnly(String cacheName, String key, Object value, CacheConfig config) {
        return Mono.fromRunnable(() -> {
            Cache<String, Object> l1Cache = l1Caches.get(cacheName);
            if (l1Cache != null) {
                l1Cache.put(key, value);
            }
        });
    }

    @lombok.Data
    public static class CacheConfig {
        private CacheStrategy strategy = CacheStrategy.CACHE_FIRST;
        private long maxSize = 10000;
        private long ttlSeconds = 3600;
        private long ttiSeconds = 1800;
        private boolean l2Enabled = true;
    }
}
