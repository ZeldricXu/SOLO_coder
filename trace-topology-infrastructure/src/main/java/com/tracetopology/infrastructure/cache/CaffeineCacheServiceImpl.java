package com.tracetopology.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tracetopology.spi.cache.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
@Service
public class CaffeineCacheServiceImpl implements CacheService {

    private final Cache<String, Object> caffeineCache;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public CaffeineCacheServiceImpl() {
        this.caffeineCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        log.debug("缓存写入: key={}", key);
        caffeineCache.policy().expireAfterWrite().ifPresent(policy ->
                policy.put(key, value, ttl));
        if (ttl == null) {
            caffeineCache.put(key, value);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = caffeineCache.getIfPresent(key);
        if (value == null) {
            log.debug("缓存未命中: key={}", key);
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            log.debug("缓存命中: key={}", key);
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader, Duration ttl) {
        return (T) caffeineCache.get(key, k -> {
            log.debug("缓存加载: key={}", key);
            T value = loader.get();
            return value;
        });
    }

    @Override
    public void invalidate(String key) {
        log.debug("缓存失效: key={}", key);
        caffeineCache.invalidate(key);
    }

    @Override
    public void invalidateAll(String pattern) {
        log.debug("批量缓存失效: pattern={}", pattern);
        if ("*".equals(pattern)) {
            caffeineCache.invalidateAll();
        } else {
            caffeineCache.asMap().keySet().stream()
                    .filter(k -> k.matches(pattern.replace("*", ".*")))
                    .forEach(caffeineCache::invalidate);
        }
    }

    @Override
    public boolean exists(String key) {
        return caffeineCache.getIfPresent(key) != null;
    }

    @Override
    public long increment(String key, long delta) {
        AtomicLong counter = counters.computeIfAbsent(key, k -> new AtomicLong(0));
        return counter.addAndGet(delta);
    }

    @Override
    public long decrement(String key, long delta) {
        AtomicLong counter = counters.computeIfAbsent(key, k -> new AtomicLong(0));
        return counter.addAndGet(-delta);
    }
}
