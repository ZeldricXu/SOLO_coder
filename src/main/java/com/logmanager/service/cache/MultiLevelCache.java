package com.logmanager.service.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class MultiLevelCache<K, V> implements Cache<K, V> {

    private final String name;
    private final Cache<K, V> l1Cache;
    private final Cache<K, V> l2Cache;
    private final CacheLoader<K, V> cacheLoader;

    private final Counter l1HitCounter;
    private final Counter l1MissCounter;
    private final Counter l2HitCounter;
    private final Counter l2MissCounter;
    private final Timer l1GetTimer;
    private final Timer l2GetTimer;
    private final AtomicLong warmupCount = new AtomicLong(0);

    public MultiLevelCache(String name, Cache<K, V> l1Cache, Cache<K, V> l2Cache,
                           CacheLoader<K, V> cacheLoader, MeterRegistry meterRegistry) {
        this.name = name;
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
        this.cacheLoader = cacheLoader;

        this.l1HitCounter = Counter.builder("cache.l1.hits")
                .tag("cache", name)
                .register(meterRegistry);
        this.l1MissCounter = Counter.builder("cache.l1.misses")
                .tag("cache", name)
                .register(meterRegistry);
        this.l2HitCounter = Counter.builder("cache.l2.hits")
                .tag("cache", name)
                .register(meterRegistry);
        this.l2MissCounter = Counter.builder("cache.l2.misses")
                .tag("cache", name)
                .register(meterRegistry);
        this.l1GetTimer = Timer.builder("cache.l1.get.duration")
                .tag("cache", name)
                .register(meterRegistry);
        this.l2GetTimer = Timer.builder("cache.l2.get.duration")
                .tag("cache", name)
                .register(meterRegistry);

        log.info("Multi-level cache '{}' initialized with L1 and L2", name);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Mono<V> get(K key) {
        long start = System.nanoTime();
        return l1Cache.get(key)
                .doOnNext(v -> {
                    l1HitCounter.increment();
                    l1GetTimer.record(Duration.ofNanos(System.nanoTime() - start));
                    log.debug("L1 cache hit for key: {}", key);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    l1MissCounter.increment();
                    l1GetTimer.record(Duration.ofNanos(System.nanoTime() - start));
                    log.debug("L1 cache miss for key: {}", key);
                    long l2Start = System.nanoTime();
                    return l2Cache.get(key)
                            .doOnNext(v -> {
                                l2HitCounter.increment();
                                l2GetTimer.record(Duration.ofNanos(System.nanoTime() - l2Start));
                                log.debug("L2 cache hit for key: {}", key);
                                l1Cache.put(key, v).subscribe();
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                l2MissCounter.increment();
                                l2GetTimer.record(Duration.ofNanos(System.nanoTime() - l2Start));
                                log.debug("L2 cache miss for key: {}", key);
                                return Mono.empty();
                            }));
                }));
    }

    @Override
    public Mono<Void> put(K key, V value) {
        return Mono.when(
                l1Cache.put(key, value),
                l2Cache.put(key, value)
        );
    }

    @Override
    public Mono<Void> put(K key, V value, Duration ttl) {
        return Mono.when(
                l1Cache.put(key, value, ttl),
                l2Cache.put(key, value, ttl)
        );
    }

    @Override
    public Mono<Void> invalidate(K key) {
        return Mono.when(
                l1Cache.invalidate(key),
                l2Cache.invalidate(key)
        );
    }

    @Override
    public Mono<Void> invalidateAll() {
        return Mono.when(
                l1Cache.invalidateAll(),
                l2Cache.invalidateAll()
        );
    }

    @Override
    public Mono<Boolean> contains(K key) {
        return l1Cache.contains(key)
                .flatMap(hasInL1 -> hasInL1 ? Mono.just(true) : l2Cache.contains(key));
    }

    @Override
    public Mono<Long> size() {
        return l1Cache.size().zipWith(l2Cache.size())
                .map(tuple -> tuple.getT1() + tuple.getT2());
    }

    public Mono<Void> warmup() {
        if (cacheLoader == null) {
            log.warn("No cache loader configured for cache '{}', skipping warmup", name);
            return Mono.empty();
        }

        log.info("Starting cache warmup for '{}'", name);
        long start = System.currentTimeMillis();

        return cacheLoader.loadAll()
                .flatMapMany(map -> reactor.core.publisher.Flux.fromIterable(map.entrySet()))
                .flatMap(entry -> put(entry.getKey(), entry.getValue()))
                .then()
                .doOnSuccess(v -> {
                    long duration = System.currentTimeMillis() - start;
                    long count = warmupCount.incrementAndGet();
                    log.info("Cache warmup completed for '{}' in {}ms, warmup count: {}", name, duration, count);
                })
                .doOnError(e -> log.error("Cache warmup failed for '{}': {}", name, e.getMessage()));
    }

    public Cache<K, V> getL1Cache() {
        return l1Cache;
    }

    public Cache<K, V> getL2Cache() {
        return l2Cache;
    }

    public long getWarmupCount() {
        return warmupCount.get();
    }
}
