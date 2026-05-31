package com.parking.platform.gateway.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.parking.platform.gateway.config.RateLimitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RateLimitConfig rateLimitConfig;
    private final Map<String, SlidingWindowLimiter> minuteLimiters;
    private final Map<String, SlidingWindowLimiter> hourLimiters;
    private final Cache<String, Boolean> blockedKeys;

    public RateLimitService(RateLimitConfig rateLimitConfig) {
        this.rateLimitConfig = rateLimitConfig;
        this.minuteLimiters = new ConcurrentHashMap<>(1024);
        this.hourLimiters = new ConcurrentHashMap<>(1024);
        this.blockedKeys = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
    }

    public boolean tryAcquire(String key) {
        if (!rateLimitConfig.isEnabled()) {
            return true;
        }

        if (isBlocked(key)) {
            return false;
        }

        SlidingWindowLimiter minuteLimiter = getOrCreateMinuteLimiter(key);
        SlidingWindowLimiter hourLimiter = getOrCreateHourLimiter(key);

        if (!minuteLimiter.tryAcquire()) {
            log.warn("Rate limit exceeded (per minute) for key: {}", key);
            return false;
        }

        if (!hourLimiter.tryAcquire()) {
            minuteLimiter.release();
            log.warn("Rate limit exceeded (per hour) for key: {}", key);
            return false;
        }

        return true;
    }

    public RateLimitInfo getRateLimitInfo(String key) {
        SlidingWindowLimiter minuteLimiter = minuteLimiters.get(key);
        SlidingWindowLimiter hourLimiter = hourLimiters.get(key);

        long minuteLimit = rateLimitConfig.getDefaultLimit().getPerMinute();
        long hourLimit = rateLimitConfig.getDefaultLimit().getPerHour();
        long minuteRemaining = minuteLimiter != null ? minuteLimiter.getRemaining(minuteLimit) : minuteLimit;
        long hourRemaining = hourLimiter != null ? hourLimiter.getRemaining(hourLimit) : hourLimit;

        return new RateLimitInfo(
                minuteLimit,
                minuteRemaining,
                hourLimit,
                hourRemaining,
                isBlocked(key)
        );
    }

    public void blockKey(String key, long durationMillis) {
        blockedKeys.put(key, true);
        log.info("Blocked key: {} for {}ms", key, durationMillis);
    }

    public void unblockKey(String key) {
        blockedKeys.invalidate(key);
        log.info("Unblocked key: {}", key);
    }

    public boolean isBlocked(String key) {
        return blockedKeys.getIfPresent(key) != null;
    }

    private SlidingWindowLimiter getOrCreateMinuteLimiter(String key) {
        return minuteLimiters.computeIfAbsent(key, k ->
                new SlidingWindowLimiter(60, TimeUnit.SECONDS));
    }

    private SlidingWindowLimiter getOrCreateHourLimiter(String key) {
        return hourLimiters.computeIfAbsent(key, k ->
                new SlidingWindowLimiter(1, TimeUnit.HOURS));
    }

    public static final class SlidingWindowLimiter {
        private final long windowMillis;
        private final AtomicLong currentWindowStart;
        private final AtomicLong currentWindowCount;
        private final AtomicLong previousWindowCount;

        public SlidingWindowLimiter(long window, TimeUnit unit) {
            this.windowMillis = unit.toMillis(window);
            this.currentWindowStart = new AtomicLong(System.currentTimeMillis());
            this.currentWindowCount = new AtomicLong(0);
            this.previousWindowCount = new AtomicLong(0);
        }

        public boolean tryAcquire() {
            while (true) {
                long now = System.currentTimeMillis();
                long windowStart = currentWindowStart.get();
                long elapsed = now - windowStart;

                if (elapsed >= windowMillis * 2) {
                    if (currentWindowStart.compareAndSet(windowStart, now)) {
                        currentWindowCount.set(0);
                        previousWindowCount.set(0);
                    }
                    continue;
                }

                if (elapsed >= windowMillis) {
                    long newWindowStart = windowStart + ((elapsed / windowMillis) * windowMillis);
                    if (currentWindowStart.compareAndSet(windowStart, newWindowStart)) {
                        previousWindowCount.set(currentWindowCount.getAndSet(0));
                    }
                    continue;
                }

                break;
            }

            return currentWindowCount.incrementAndGet() <= 100;
        }

        public void release() {
            long curr = currentWindowCount.get();
            if (curr > 0) {
                currentWindowCount.decrementAndGet();
            }
        }

        public long getRemaining(long limit) {
            long remaining = limit - currentWindowCount.get();
            return Math.max(0, remaining);
        }
    }

    public record RateLimitInfo(
            long minuteLimit,
            long minuteRemaining,
            long hourLimit,
            long hourRemaining,
            boolean blocked
    ) {}
}
