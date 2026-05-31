package com.datapipeline.data.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RedisCacheBackend implements CacheBackend {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisCacheBackend(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<Object> get(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.warn("Redis get failed for key: {}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Redis put failed for key: {}", key, e);
        }
    }

    @Override
    public void invalidate(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis invalidate failed for key: {}", key, e);
        }
    }

    @Override
    public void invalidatePattern(String pattern) {
        try {
            String redisPattern = pattern;
            if (!pattern.endsWith("*")) {
                redisPattern = pattern + "*";
            }
            Set<String> keys = redisTemplate.keys(redisPattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis pattern invalidate failed for pattern: {}", pattern, e);
        }
    }

    @Override
    public void invalidateAll() {
        try {
            Set<String> keys = redisTemplate.keys("*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis invalidate all failed", e);
        }
    }

}
