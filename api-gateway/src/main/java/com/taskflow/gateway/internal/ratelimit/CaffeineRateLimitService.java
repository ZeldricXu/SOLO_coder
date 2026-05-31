package com.taskflow.gateway.internal.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.taskflow.gateway.api.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于Caffeine的限流服务实现
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
public class CaffeineRateLimitService implements RateLimitService {

    private final Cache<String, AtomicLong> requestCounters;
    private final Cache<String, Long> windowStartTimes;

    public CaffeineRateLimitService() {
        this.requestCounters = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();

        this.windowStartTimes = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, int limit) {
        return Mono.fromCallable(() -> {
            long now = System.currentTimeMillis();
            Long windowStart = windowStartTimes.getIfPresent(key);

            if (windowStart == null || now - windowStart > 1000) {
                windowStartTimes.put(key, now);
                requestCounters.put(key, new AtomicLong(1));
                return true;
            }

            AtomicLong counter = requestCounters.get(key, k -> new AtomicLong(0));
            long current = counter.incrementAndGet();

            if (current > limit) {
                log.warn("Rate limit exceeded for key: {}, limit: {}", key, limit);
                return false;
            }

            return true;
        });
    }

    @Override
    public Mono<Long> getRemaining(String key, int limit) {
        return Mono.fromCallable(() -> {
            AtomicLong counter = requestCounters.getIfPresent(key);
            if (counter == null) {
                return (long) limit;
            }
            long current = counter.get();
            return Math.max(0, limit - current);
        });
    }

    @Override
    public void reset(String key) {
        requestCounters.invalidate(key);
        windowStartTimes.invalidate(key);
        log.debug("Rate limit reset for key: {}", key);
    }
}
