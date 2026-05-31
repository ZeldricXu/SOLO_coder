package com.tsdbproxy.metadata.crawler.impl.cache;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tsdbproxy.metadata.crawler.spi.Cache;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class L1LocalCache<K, V> implements Cache<K, V> {

    private final AsyncCache<K, V> cache;
    private final String cacheName;
    private final Duration defaultTtl;

    public L1LocalCache(String cacheName, int maxSize, Duration defaultTtl) {
        this.cacheName = cacheName;
        this.defaultTtl = defaultTtl;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(defaultTtl.toMillis(), TimeUnit.MILLISECONDS)
                .recordStats()
                .buildAsync();
    }

    @Override
    public Mono<V> get(K key) {
        CompletableFuture<V> future = cache.getIfPresent(key);
        if (future == null) {
            log.debug("L1缓存未命中: key={}", key);
            return Mono.empty();
        }
        return Mono.fromFuture(future)
                .doOnNext(v -> log.debug("L1缓存命中: key={}", key));
    }

    @Override
    public Mono<Void> put(K key, V value, Duration ttl) {
        return Mono.fromRunnable(() -> {
            cache.put(key, CompletableFuture.completedFuture(value));
            log.debug("L1缓存写入: key={}", key);
        });
    }

    @Override
    public Mono<Void> invalidate(K key) {
        return Mono.fromRunnable(() -> {
            cache.synchronous().invalidate(key);
            log.debug("L1缓存失效: key={}", key);
        });
    }

    @Override
    public Mono<Void> invalidateAll() {
        return Mono.fromRunnable(() -> {
            cache.synchronous().invalidateAll();
            log.info("L1缓存全部失效");
        });
    }

    @Override
    public String getCacheName() {
        return cacheName + "_L1";
    }
}
