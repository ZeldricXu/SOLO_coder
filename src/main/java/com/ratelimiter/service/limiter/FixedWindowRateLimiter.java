package com.ratelimiter.service.limiter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.repository.RateLimitCounterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class FixedWindowRateLimiter {
    
    private final RateLimitCounterRepository counterRepository;
    private final ConcurrentHashMap<String, LocalCounter> localCounters;
    private final Cache<String, Object> cache;
    
    public FixedWindowRateLimiter(RateLimitCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
        this.localCounters = new ConcurrentHashMap<>();
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
        int windowSize = policy.getWindowSize();
        String responseMessage = policy.getResponseMessage();
        int responseCode = policy.getResponseCode();
        
        try {
            return tryAcquireWithRedis(target, threshold, windowSize, responseMessage, responseCode);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis connection failed, falling back to local counter for target: {}", target);
            return tryAcquireWithLocalCounter(target, threshold, windowSize, responseMessage, responseCode);
        } catch (Exception e) {
            log.error("Error in fixed window rate limiting for target: {}", target, e);
            return tryAcquireWithLocalCounter(target, threshold, windowSize, responseMessage, responseCode);
        }
    }
    
    private RateLimitResult tryAcquireWithRedis(String target, int threshold, int windowSize, 
                                                  String responseMessage, int responseCode) {
        long currentCount = counterRepository.incrementCounter(target, windowSize);
        log.debug("Fixed window counter for target {}: {}/{}", target, currentCount, threshold);
        
        if (currentCount > threshold) {
            log.info("Rate limit exceeded for target: {} (count: {}, threshold: {})", target, currentCount, threshold);
            return RateLimitResult.rejected(responseMessage, responseCode);
        }
        
        int remaining = Math.max(0, threshold - (int) currentCount);
        return RateLimitResult.allowed(remaining);
    }
    
    private RateLimitResult tryAcquireWithLocalCounter(String target, int threshold, int windowSize,
                                                         String responseMessage, int responseCode) {
        long now = System.currentTimeMillis();
        long windowStart = now - (windowSize * 1000L);
        
        LocalCounter counter = localCounters.computeIfAbsent(target, k -> new LocalCounter(now));
        
        if (counter.getLastResetTime() < windowStart) {
            counter.reset(now);
        }
        
        long currentCount = counter.incrementAndGet();
        log.debug("Local fixed window counter for target {}: {}/{}", target, currentCount, threshold);
        
        if (currentCount > threshold) {
            log.info("Local rate limit exceeded for target: {} (count: {}, threshold: {})", target, currentCount, threshold);
            return RateLimitResult.rejected(responseMessage, responseCode);
        }
        
        int remaining = Math.max(0, threshold - (int) currentCount);
        return RateLimitResult.allowed(remaining);
    }
    
    private static class LocalCounter {
        private final AtomicLong count;
        private volatile long lastResetTime;
        
        public LocalCounter(long resetTime) {
            this.count = new AtomicLong(0);
            this.lastResetTime = resetTime;
        }
        
        public long incrementAndGet() {
            return count.incrementAndGet();
        }
        
        public long getLastResetTime() {
            return lastResetTime;
        }
        
        public void reset(long resetTime) {
            count.set(0);
            lastResetTime = resetTime;
        }
    }
}