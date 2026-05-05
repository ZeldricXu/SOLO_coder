package com.ratelimiter.repository;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Repository
public class RateLimitCounterRepository {
    
    private static final String COUNTER_KEY_PREFIX = "ratelimiter:counter:";
    private static final String WINDOW_KEY_PREFIX = "ratelimiter:window:";
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    public RateLimitCounterRepository(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    public long incrementCounter(String target, int windowSeconds) {
        String key = getCounterKey(target);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }
        return count != null ? count : 0;
    }
    
    public long getCounter(String target) {
        String key = getCounterKey(target);
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    public void resetCounter(String target) {
        String key = getCounterKey(target);
        redisTemplate.delete(key);
    }
    
    public long addTimestampToWindow(String target, long timestamp, int windowSeconds) {
        String key = getWindowKey(target);
        redisTemplate.opsForZSet().add(key, timestamp, timestamp);
        redisTemplate.expire(key, windowSeconds + 10, TimeUnit.SECONDS);
        
        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000L);
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
        
        Long count = redisTemplate.opsForZSet().size(key);
        return count != null ? count : 0;
    }
    
    public long getWindowCount(String target, int windowSeconds) {
        String key = getWindowKey(target);
        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000L);
        
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
        
        Long count = redisTemplate.opsForZSet().count(key, windowStart, now);
        return count != null ? count : 0;
    }
    
    public Set<Object> getWindowTimestamps(String target, int windowSeconds) {
        String key = getWindowKey(target);
        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000L);
        
        return redisTemplate.opsForZSet().rangeByScore(key, windowStart, now);
    }
    
    public void clearWindow(String target) {
        String key = getWindowKey(target);
        redisTemplate.delete(key);
    }
    
    private String getCounterKey(String target) {
        return COUNTER_KEY_PREFIX + target;
    }
    
    private String getWindowKey(String target) {
        return WINDOW_KEY_PREFIX + target;
    }
}