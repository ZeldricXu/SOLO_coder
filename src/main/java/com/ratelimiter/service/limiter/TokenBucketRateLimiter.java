package com.ratelimiter.service.limiter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.repository.TokenBucketRepository;
import com.ratelimiter.repository.TokenBucketRepository.TokenBucketState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class TokenBucketRateLimiter {
    
    private final TokenBucketRepository bucketRepository;
    private final ConcurrentHashMap<String, LocalTokenBucket> localBuckets;
    private final Cache<String, Object> cache;
    
    public TokenBucketRateLimiter(TokenBucketRepository bucketRepository) {
        this.bucketRepository = bucketRepository;
        this.localBuckets = new ConcurrentHashMap<>();
        this.cache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .build();
    }
    
    public RateLimitResult tryAcquire(String target, RateLimitPolicy policy) {
        if (policy == null) {
            log.info("No policy found for target: {}, allowing request", target);
            return RateLimitResult.allowed(Integer.MAX_VALUE);
        }
        
        int threshold = policy.getThreshold();
        int burstSize = policy.getBurstSize();
        int windowSize = policy.getWindowSize();
        String responseMessage = policy.getResponseMessage();
        int responseCode = policy.getResponseCode();
        
        int capacity = threshold + burstSize;
        int fillRate = calculateFillRate(threshold, windowSize);
        
        try {
            return tryAcquireWithRedis(target, capacity, fillRate, responseMessage, responseCode);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis connection failed, falling back to local token bucket for target: {}", target);
            return tryAcquireWithLocalBucket(target, capacity, fillRate, responseMessage, responseCode);
        } catch (Exception e) {
            log.error("Error in token bucket rate limiting for target: {}", target, e);
            return tryAcquireWithLocalBucket(target, capacity, fillRate, responseMessage, responseCode);
        }
    }
    
    private int calculateFillRate(int threshold, int windowSize) {
        if (windowSize <= 0) {
            return threshold;
        }
        return Math.max(1, threshold / windowSize);
    }
    
    private RateLimitResult tryAcquireWithRedis(String target, int capacity, int fillRate,
                                                  String responseMessage, int responseCode) {
        TokenBucketState state = bucketRepository.getBucketState(target);
        long now = System.currentTimeMillis();
        
        if (state == null) {
            state = new TokenBucketState(capacity, capacity, fillRate);
        } else {
            refillTokens(state, now);
        }
        
        if (state.getTokens() <= 0) {
            log.info("Token bucket rate limit exceeded for target: {} (tokens: 0, capacity: {})", 
                    target, capacity);
            bucketRepository.saveBucketState(target, state, 3600);
            return RateLimitResult.rejected(responseMessage, responseCode);
        }
        
        state.setTokens(state.getTokens() - 1);
        state.setLastFillTime(now);
        bucketRepository.saveBucketState(target, state, 3600);
        
        log.debug("Token bucket for target {}: tokens={}/{}, fillRate={}", 
                target, state.getTokens(), capacity, fillRate);
        
        return RateLimitResult.allowed(state.getTokens());
    }
    
    private RateLimitResult tryAcquireWithLocalBucket(String target, int capacity, int fillRate,
                                                        String responseMessage, int responseCode) {
        LocalTokenBucket bucket = localBuckets.computeIfAbsent(target, 
                k -> new LocalTokenBucket(capacity, fillRate));
        
        long now = System.currentTimeMillis();
        
        synchronized (bucket) {
            refillTokens(bucket, now);
            
            if (bucket.getTokens() <= 0) {
                log.info("Local token bucket rate limit exceeded for target: {} (tokens: 0, capacity: {})",
                        target, capacity);
                return RateLimitResult.rejected(responseMessage, responseCode);
            }
            
            bucket.setTokens(bucket.getTokens() - 1);
            bucket.setLastFillTime(now);
            
            log.debug("Local token bucket for target {}: tokens={}/{}, fillRate={}",
                    target, bucket.getTokens(), capacity, fillRate);
            
            return RateLimitResult.allowed(bucket.getTokens());
        }
    }
    
    private void refillTokens(TokenBucketState state, long now) {
        long elapsedMs = now - state.getLastFillTime();
        if (elapsedMs > 0) {
            int tokensToAdd = (int) (elapsedMs * state.getFillRate() / 1000);
            if (tokensToAdd > 0) {
                int newTokens = Math.min(state.getCapacity(), state.getTokens() + tokensToAdd);
                state.setTokens(newTokens);
                state.setLastFillTime(now);
            }
        }
    }
    
    private void refillTokens(LocalTokenBucket bucket, long now) {
        long elapsedMs = now - bucket.getLastFillTime();
        if (elapsedMs > 0) {
            int tokensToAdd = (int) (elapsedMs * bucket.getFillRate() / 1000);
            if (tokensToAdd > 0) {
                int newTokens = Math.min(bucket.getCapacity(), bucket.getTokens() + tokensToAdd);
                bucket.setTokens(newTokens);
                bucket.setLastFillTime(now);
            }
        }
    }
    
    private static class LocalTokenBucket {
        private final int capacity;
        private final int fillRate;
        private final AtomicInteger tokens;
        private final AtomicLong lastFillTime;
        
        public LocalTokenBucket(int capacity, int fillRate) {
            this.capacity = capacity;
            this.fillRate = fillRate;
            this.tokens = new AtomicInteger(capacity);
            this.lastFillTime = new AtomicLong(System.currentTimeMillis());
        }
        
        public int getCapacity() {
            return capacity;
        }
        
        public int getFillRate() {
            return fillRate;
        }
        
        public int getTokens() {
            return tokens.get();
        }
        
        public void setTokens(int tokens) {
            this.tokens.set(tokens);
        }
        
        public long getLastFillTime() {
            return lastFillTime.get();
        }
        
        public void setLastFillTime(long time) {
            this.lastFillTime.set(time);
        }
    }
}