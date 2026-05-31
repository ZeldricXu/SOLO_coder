package com.edgeplatform.dataaccess.service;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final Cache<String, Object> caffeineCache;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final long DEFAULT_TTL_SECONDS = 300;

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key) {
        T value = (T) caffeineCache.getIfPresent(key);
        if (value != null) {
            return Optional.of(value);
        }

        try {
            value = (T) redisTemplate.opsForValue().get(key);
            if (value != null) {
                caffeineCache.put(key, value);
                return Optional.of(value);
            }
        } catch (Exception e) {
            log.warn("Redis get failed for key: {}, falling back to L1 only", key, e);
        }

        return Optional.empty();
    }

    public <T> T getOrSupply(String key, Supplier<T> supplier) {
        return getOrSupply(key, supplier, DEFAULT_TTL_SECONDS);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrSupply(String key, Supplier<T> supplier, long ttlSeconds) {
        Optional<T> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        T value = supplier.get();
        if (value != null) {
            put(key, value, ttlSeconds);
        }
        return value;
    }

    public void put(String key, Object value) {
        put(key, value, DEFAULT_TTL_SECONDS);
    }

    public void put(String key, Object value, long ttlSeconds) {
        caffeineCache.put(key, value);
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis set failed for key: {}", key, e);
        }
    }

    public void evict(String key) {
        caffeineCache.invalidate(key);
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis delete failed for key: {}", key, e);
        }
    }

    public void evictPattern(String pattern) {
        try {
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                caffeineCache.invalidateAll(keys);
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis pattern delete failed for pattern: {}", pattern, e);
        }
    }

    public boolean exists(String key) {
        if (caffeineCache.asMap().containsKey(key)) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Redis exists check failed for key: {}", key, e);
            return false;
        }
    }
}
