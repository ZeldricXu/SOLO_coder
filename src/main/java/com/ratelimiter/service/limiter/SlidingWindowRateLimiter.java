package com.ratelimiter.service.limiter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.repository.RateLimitCounterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SlidingWindowRateLimiter {
    
    private final RateLimitCounterRepository counterRepository;
    private final SlidingWindowCleanerService cleanerService;
    private final ConcurrentHashMap<String, LocalWindow> localWindows;
    private final Cache<String, Integer> targetWindowSizeCache;
    
    private static final long LOCAL_CLEANUP_INTERVAL_MS = 60000;
    
    public SlidingWindowRateLimiter(RateLimitCounterRepository counterRepository,
                                    SlidingWindowCleanerService cleanerService) {
        this.counterRepository = counterRepository;
        this.cleanerService = cleanerService;
        this.localWindows = new ConcurrentHashMap<>();
        this.targetWindowSizeCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
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
        
        targetWindowSizeCache.put(target, windowSize);
        cleanerService.registerWindowTarget(target, windowSize);
        
        try {
            return tryAcquireWithRedis(target, threshold, windowSize, responseMessage, responseCode);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis connection failed, falling back to local sliding window for target: {}", target);
            return tryAcquireWithLocalWindow(target, threshold, windowSize, responseMessage, responseCode);
        } catch (Exception e) {
            log.error("Error in sliding window rate limiting for target: {}", target, e);
            return tryAcquireWithLocalWindow(target, threshold, windowSize, responseMessage, responseCode);
        }
    }
    
    private RateLimitResult tryAcquireWithRedis(String target, int threshold, int windowSize,
                                                  String responseMessage, int responseCode) {
        long now = System.currentTimeMillis();
        
        long currentCount = counterRepository.addTimestampToWindow(target, now, windowSize);
        log.debug("Sliding window counter for target {}: {}/{}", target, currentCount, threshold);
        
        if (currentCount > threshold) {
            log.info("Sliding window rate limit exceeded for target: {} (count: {}, threshold: {})", 
                    target, currentCount, threshold);
            return RateLimitResult.rejected(responseMessage, responseCode);
        }
        
        int remaining = Math.max(0, threshold - (int) currentCount);
        return RateLimitResult.allowed(remaining);
    }
    
    private RateLimitResult tryAcquireWithLocalWindow(String target, int threshold, int windowSize,
                                                        String responseMessage, int responseCode) {
        long now = System.currentTimeMillis();
        long windowStart = now - (windowSize * 1000L);
        
        LocalWindow window = localWindows.computeIfAbsent(target, k -> new LocalWindow(windowSize));
        
        synchronized (window) {
            window.cleanupExpired(windowStart);
            
            int currentCount = window.getTimestampCount();
            log.debug("Local sliding window counter for target {}: {}/{}", target, currentCount, threshold);
            
            if (currentCount >= threshold) {
                log.info("Local sliding window rate limit exceeded for target: {} (count: {}, threshold: {})",
                        target, currentCount, threshold);
                return RateLimitResult.rejected(responseMessage, responseCode);
            }
            
            window.addTimestamp(now);
            int remaining = Math.max(0, threshold - (currentCount + 1));
            return RateLimitResult.allowed(remaining);
        }
    }
    
    @Scheduled(fixedRate = LOCAL_CLEANUP_INTERVAL_MS)
    public void cleanupLocalWindows() {
        log.debug("Starting scheduled local sliding window cleanup...");
        
        long now = System.currentTimeMillis();
        int cleanedCount = 0;
        
        for (Map.Entry<String, LocalWindow> entry : localWindows.entrySet()) {
            String target = entry.getKey();
            LocalWindow window = entry.getValue();
            
            Integer windowSize = targetWindowSizeCache.getIfPresent(target);
            if (windowSize == null) {
                windowSize = 60;
            }
            
            long windowStart = now - (windowSize * 1000L);
            
            synchronized (window) {
                window.cleanupExpired(windowStart);
                
                if (window.getTimestampCount() == 0) {
                    localWindows.remove(target);
                    cleanedCount++;
                }
            }
        }
        
        if (cleanedCount > 0) {
            log.debug("Cleaned up {} empty local windows", cleanedCount);
        }
    }
    
    public void forceCleanupAll() {
        log.info("Force cleaning up all sliding windows...");
        cleanerService.forceCleanupAllWindows();
        localWindows.clear();
        log.info("Force cleanup completed");
    }
    
    private static class LocalWindow {
        private final LinkedList<Long> timestamps;
        private final int windowSize;
        
        public LocalWindow(int windowSize) {
            this.timestamps = new LinkedList<>();
            this.windowSize = windowSize;
        }
        
        public void addTimestamp(long timestamp) {
            timestamps.addLast(timestamp);
        }
        
        public int getTimestampCount() {
            return timestamps.size();
        }
        
        public void cleanupExpired(long windowStart) {
            Iterator<Long> iterator = timestamps.iterator();
            while (iterator.hasNext()) {
                Long timestamp = iterator.next();
                if (timestamp < windowStart) {
                    iterator.remove();
                } else {
                    break;
                }
            }
        }
    }
}