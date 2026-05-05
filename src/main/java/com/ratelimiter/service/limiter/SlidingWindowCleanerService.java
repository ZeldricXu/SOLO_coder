package com.ratelimiter.service.limiter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimiter.repository.RateLimitCounterRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SlidingWindowCleanerService {
    
    private static final String WINDOW_KEY_PREFIX = "ratelimiter:window:";
    private static final String WINDOW_INDEX_KEY = "ratelimiter:window:index";
    private static final long DEFAULT_WINDOW_SECONDS = 60;
    private static final long MIN_CLEANUP_INTERVAL_MS = 1000;
    private static final long MAX_CLEANUP_INTERVAL_MS = 30000;
    private static final double CLEANUP_RATIO = 0.1;
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final RateLimitCounterRepository counterRepository;
    private final Cache<String, Long> windowTargetCache;
    private final ConcurrentHashMap<String, Long> targetWindowSizes;
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledCleanups;
    private final ConcurrentHashMap<Long, Set<String>> windowGroupTargets;
    private final TaskScheduler taskScheduler;
    
    public SlidingWindowCleanerService(RedisTemplate<String, Object> redisTemplate,
                                        RateLimitCounterRepository counterRepository) {
        this.redisTemplate = redisTemplate;
        this.counterRepository = counterRepository;
        this.windowTargetCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
        this.targetWindowSizes = new ConcurrentHashMap<>();
        this.scheduledCleanups = new ConcurrentHashMap<>();
        this.windowGroupTargets = new ConcurrentHashMap<>();
        this.taskScheduler = new ConcurrentTaskScheduler();
    }
    
    public void registerWindowTarget(String target, int windowSeconds) {
        targetWindowSizes.put(target, (long) windowSeconds);
        windowTargetCache.put(target, (long) windowSeconds);
        redisTemplate.opsForSet().add(WINDOW_INDEX_KEY, target);
        
        scheduleCleanupForWindowSize(windowSeconds);
        
        log.info("Registered window target: {} with window size: {}s, cleanup interval: {}ms",
                target, windowSeconds, calculateCleanupInterval(windowSeconds));
    }
    
    private void scheduleCleanupForWindowSize(int windowSeconds) {
        long cleanupIntervalMs = calculateCleanupInterval(windowSeconds);
        Long windowSizeKey = (long) windowSeconds;
        
        if (scheduledCleanups.containsKey(windowSizeKey)) {
            log.debug("Cleanup already scheduled for window size: {}s", windowSeconds);
            return;
        }
        
        windowGroupTargets.computeIfAbsent(windowSizeKey, 
                k -> ConcurrentHashMap.newKeySet());
        
        ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
                () -> cleanupWindowsBySize(windowSeconds),
                cleanupIntervalMs
        );
        
        scheduledCleanups.put(windowSizeKey, future);
        log.info("Scheduled dynamic cleanup for window size: {}s with interval: {}ms",
                windowSeconds, cleanupIntervalMs);
    }
    
    private long calculateCleanupInterval(int windowSeconds) {
        long intervalMs = (long) (windowSeconds * 1000L * CLEANUP_RATIO);
        
        if (intervalMs < MIN_CLEANUP_INTERVAL_MS) {
            return MIN_CLEANUP_INTERVAL_MS;
        }
        if (intervalMs > MAX_CLEANUP_INTERVAL_MS) {
            return MAX_CLEANUP_INTERVAL_MS;
        }
        
        return intervalMs;
    }
    
    private void cleanupWindowsBySize(int windowSeconds) {
        Long windowSizeKey = (long) windowSeconds;
        Set<String> targets = windowGroupTargets.get(windowSizeKey);
        
        if (targets == null || targets.isEmpty()) {
            log.debug("No targets found for window size: {}s", windowSeconds);
            return;
        }
        
        int cleanedCount = 0;
        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000L);
        
        for (String target : targets) {
            try {
                cleanupExpiredTimestamps(target, windowStart);
                cleanedCount++;
            } catch (Exception e) {
                log.warn("Failed to cleanup window for target: {}", target, e);
            }
        }
        
        log.debug("Completed dynamic cleanup for window size {}s. Cleaned {} targets", 
                windowSeconds, cleanedCount);
    }
    
    private long getWindowSizeForTarget(String target) {
        Long cachedSize = targetWindowSizes.get(target);
        if (cachedSize != null) {
            return cachedSize;
        }
        
        cachedSize = windowTargetCache.getIfPresent(target);
        if (cachedSize != null) {
            return cachedSize;
        }
        
        return DEFAULT_WINDOW_SECONDS;
    }
    
    private void cleanupExpiredTimestamps(String target, long windowStart) {
        String key = WINDOW_KEY_PREFIX + target;
        
        ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
        
        Long removedCount = zSetOps.removeRangeByScore(key, 0, windowStart);
        
        if (removedCount != null && removedCount > 0) {
            log.debug("Cleaned up {} expired timestamps for target: {}", removedCount, target);
        }
        
        Long remainingCount = zSetOps.size(key);
        if (remainingCount != null && remainingCount == 0) {
            redisTemplate.delete(key);
            redisTemplate.opsForSet().remove(WINDOW_INDEX_KEY, target);
            windowTargetCache.invalidate(target);
            
            Long windowSize = targetWindowSizes.remove(target);
            if (windowSize != null) {
                Set<String> targets = windowGroupTargets.get(windowSize);
                if (targets != null) {
                    targets.remove(target);
                }
            }
            
            log.debug("Removed empty window key for target: {}", target);
        }
    }
    
    public void cleanupExpiredWindowsForTarget(String target, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowStart = now - (windowSeconds * 1000L);
        cleanupExpiredTimestamps(target, windowStart);
    }
    
    public void forceCleanupAllWindows() {
        log.info("Starting force cleanup of all sliding windows...");
        
        Set<Object> windowTargets = redisTemplate.opsForSet().members(WINDOW_INDEX_KEY);
        if (windowTargets == null || windowTargets.isEmpty()) {
            log.info("No windows found for force cleanup");
            return;
        }
        
        long now = System.currentTimeMillis();
        int cleanedCount = 0;
        
        for (Object targetObj : windowTargets) {
            String target = (String) targetObj;
            try {
                Long windowSeconds = getWindowSizeForTarget(target);
                long windowStart = now - (windowSeconds * 1000L);
                cleanupExpiredTimestamps(target, windowStart);
                cleanedCount++;
            } catch (Exception e) {
                log.warn("Failed to force cleanup window for target: {}", target, e);
            }
        }
        
        log.info("Force cleanup completed. Cleaned {} targets", cleanedCount);
    }
    
    public long getWindowCount(String target) {
        String key = WINDOW_KEY_PREFIX + target;
        ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
        Long count = zSetOps.size(key);
        return count != null ? count : 0;
    }
    
    public Map<Long, Long> getCleanupSchedules() {
        Map<Long, Long> schedules = new ConcurrentHashMap<>();
        for (Map.Entry<Long, ScheduledFuture<?>> entry : scheduledCleanups.entrySet()) {
            Long windowSize = entry.getKey();
            schedules.put(windowSize, calculateCleanupInterval(windowSize.intValue()));
        }
        return schedules;
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down sliding window cleaner service...");
        
        for (Map.Entry<Long, ScheduledFuture<?>> entry : scheduledCleanups.entrySet()) {
            ScheduledFuture<?> future = entry.getValue();
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }
        
        scheduledCleanups.clear();
        log.info("Sliding window cleaner service shutdown completed");
    }
}