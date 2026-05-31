package com.metricplatform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${metric-platform.rate-limit.default-capacity:100}")
    private int defaultCapacity;

    @Value("${metric-platform.rate-limit.default-refill-tokens:10}")
    private int defaultRefillTokens;

    @Value("${metric-platform.rate-limit.default-refill-duration:1s}")
    private Duration defaultRefillDuration;

    private final Map<String, LocalRateBucket> localBuckets = new ConcurrentHashMap<>();

    public Mono<Boolean> tryAcquire(String key) {
        return tryAcquire(key, defaultCapacity, defaultRefillTokens, defaultRefillDuration);
    }

    public Mono<Boolean> tryAcquire(String key, int capacity, int refillTokens, Duration refillDuration) {
        try {
            return tryAcquireRedis(key, capacity, refillTokens, refillDuration)
                    .onErrorResume(e -> {
                        log.warn("Redis限流失败，降级到本地限流: {}", e.getMessage());
                        return Mono.just(tryAcquireLocal(key, capacity, refillTokens, refillDuration));
                    });
        } catch (Exception e) {
            log.warn("限流服务异常，降级到本地限流: {}", e.getMessage());
            return Mono.just(tryAcquireLocal(key, capacity, refillTokens, refillDuration));
        }
    }

    private Mono<Boolean> tryAcquireRedis(String key, int capacity, int refillTokens, Duration refillDuration) {
        String redisKey = "rate_limit:" + key;
        long refillInterval = refillDuration.toMillis();
        long now = System.currentTimeMillis();

        return redisTemplate.opsForValue().get(redisKey)
                .map(raw -> {
                    String[] parts = raw.split(":");
                    long lastRefill = Long.parseLong(parts[0]);
                    int tokens = Integer.parseInt(parts[1]);
                    long elapsed = now - lastRefill;
                    int refilledTokens = (int) (elapsed / refillInterval) * refillTokens;
                    int newTokens = Math.min(capacity, tokens + refilledTokens);
                    long newLastRefill = lastRefill + (elapsed / refillInterval) * refillInterval;

                    if (newTokens > 0) {
                        int remaining = newTokens - 1;
                        return redisTemplate.opsForValue().set(redisKey, newLastRefill + ":" + remaining, refillDuration.multipliedBy(2))
                                .thenReturn(true);
                    }
                    return Mono.just(false);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    int remaining = capacity - 1;
                    return redisTemplate.opsForValue().set(redisKey, now + ":" + remaining, refillDuration.multipliedBy(2))
                            .thenReturn(true);
                }))
                .flatMap(m -> m instanceof Boolean ? Mono.just((Boolean) m) : (Mono<Boolean>) m);
    }

    private boolean tryAcquireLocal(String key, int capacity, int refillTokens, Duration refillDuration) {
        LocalRateBucket bucket = localBuckets.computeIfAbsent(key, k -> new LocalRateBucket(capacity));

        synchronized (bucket) {
            long now = System.currentTimeMillis();
            long elapsed = now - bucket.lastRefillTime;
            long refillInterval = refillDuration.toMillis();

            if (elapsed >= refillInterval) {
                long periods = elapsed / refillInterval;
                bucket.tokens = Math.min(capacity, bucket.tokens + (int) periods * refillTokens);
                bucket.lastRefillTime = bucket.lastRefillTime + periods * refillInterval;
            }

            if (bucket.tokens > 0) {
                bucket.tokens--;
                return true;
            }
            return false;
        }
    }

    public Mono<Map<String, Object>> getRemaining(String key) {
        String redisKey = "rate_limit:" + key;
        return redisTemplate.opsForValue().get(redisKey)
                .map(raw -> {
                    String[] parts = raw.split(":");
                    int tokens = Integer.parseInt(parts[1]);
                    Map<String, Object> result = new java.util.HashMap<>();
                    result.put("key", key);
                    result.put("remaining", tokens);
                    result.put("capacity", defaultCapacity);
                    return result;
                })
                .defaultIfEmpty(new java.util.HashMap<>(Map.of(
                        "key", key,
                        "remaining", defaultCapacity,
                        "capacity", defaultCapacity
                )));
    }

    private static class LocalRateBucket {
        int tokens;
        long lastRefillTime;

        LocalRateBucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }
    }
}
