package com.ratelimiter.repository;

import lombok.Data;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

@Repository
public class QuotaRepository {
    
    private static final String QUOTA_KEY_PREFIX = "ratelimiter:quota:";
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    public QuotaRepository(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    public void saveQuota(String clientId, String target, QuotaState state, int ttlSeconds) {
        String key = getQuotaKey(clientId, target);
        redisTemplate.opsForValue().set(key, state, ttlSeconds, TimeUnit.SECONDS);
    }
    
    public QuotaState getQuota(String clientId, String target) {
        String key = getQuotaKey(clientId, target);
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof QuotaState) {
            return (QuotaState) value;
        }
        return null;
    }
    
    public long decrementQuota(String clientId, String target) {
        String key = getQuotaKey(clientId, target);
        Long value = redisTemplate.opsForValue().decrement(key);
        return value != null ? value : 0;
    }
    
    public void deleteQuota(String clientId, String target) {
        String key = getQuotaKey(clientId, target);
        redisTemplate.delete(key);
    }
    
    private String getQuotaKey(String clientId, String target) {
        return QUOTA_KEY_PREFIX + clientId + ":" + target;
    }
    
    @Data
    public static class QuotaState implements Serializable {
        private int totalQuota;
        private int usedQuota;
        private String period;
        
        public QuotaState() {}
        
        public QuotaState(int totalQuota, String period) {
            this.totalQuota = totalQuota;
            this.usedQuota = 0;
            this.period = period;
        }
        
        public int getRemainingQuota() {
            return Math.max(0, totalQuota - usedQuota);
        }
    }
}