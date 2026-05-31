package com.logmanager.service.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CaffeineCache<K, V> implements Cache<K, V> {

    private final String name;
    private final com.github.benmanes.caffeine.cache.Cache<K, V> cache;
    private final Duration defaultTtl;

    public CaffeineCache(String name, Duration defaultTtl, long maxSize) {
        this.name = name;
        this.defaultTtl = defaultTtl;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(defaultTtl.toMillis(), TimeUnit.MILLISECONDS)
                .maximumSize(maxSize)
                .recordStats()
                .build();
        log.info("Caffeine L1 cache '{}' initialized with maxSize={}, defaultTtl={}", name, maxSize, defaultTtl);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Mono<V> get(K key) {
        return Mono.justOrEmpty(cache.getIfPresent(key));
    }

    @Override
    public Mono<Void> put(K key, V value) {
        return put(key, value, defaultTtl);
    }

    @Override
    public Mono<Void> put(K key, V value, Duration ttl) {
        cache.put(key, value);
        return Mono.empty();
    }

    @Override
    public Mono<Void> invalidate(K key) {
        cache.invalidate(key);
        return Mono.empty();
    }

    @Override
    public Mono<Void> invalidateAll() {
        cache.invalidateAll();
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> contains(K key) {
        return Mono.just(cache.getIfPresent(key) != null);
    }

    @Override
    public Mono<Long> size() {
        return Mono.just(cache.estimatedSize());
    }

    @PreDestroy
    public void shutdown() {
        cache.cleanUp();
        log.info("Caffeine cache '{}' shutdown, stats: {}", name, cache.stats());
    }

    public com.github.benmanes.caffeine.cache.stats.CacheStats getStats() {
        return cache.stats();
    }
}
