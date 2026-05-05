package com.ratelimiter.repository;

import lombok.Data;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

@Repository
public class TokenBucketRepository {
    
    private static final String TOKEN_BUCKET_KEY_PREFIX = "ratelimiter:tokenbucket:";
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    public TokenBucketRepository(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    public void saveBucketState(String target, TokenBucketState state, int ttlSeconds) {
        String key = getBucketKey(target);
        redisTemplate.opsForValue().set(key, state, ttlSeconds, TimeUnit.SECONDS);
    }
    
    public TokenBucketState getBucketState(String target) {
        String key = getBucketKey(target);
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof TokenBucketState) {
            return (TokenBucketState) value;
        }
        return null;
    }
    
    public void deleteBucket(String target) {
        String key = getBucketKey(target);
        redisTemplate.delete(key);
    }
    
    private String getBucketKey(String target) {
        return TOKEN_BUCKET_KEY_PREFIX + target;
    }
    
    @Data
    public static class TokenBucketState implements Serializable {
        private int capacity;
        private int tokens;
        private int fillRate;
        private long lastFillTime;
        
        public TokenBucketState() {}
        
        public TokenBucketState(int capacity, int tokens, int fillRate) {
            this.capacity = capacity;
            this.tokens = tokens;
            this.fillRate = fillRate;
            this.lastFillTime = System.currentTimeMillis();
        }
    }
}