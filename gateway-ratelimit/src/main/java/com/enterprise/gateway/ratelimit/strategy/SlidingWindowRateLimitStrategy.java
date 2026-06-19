package com.enterprise.gateway.ratelimit.strategy;

import com.enterprise.gateway.common.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowRateLimitStrategy implements RateLimitStrategy {

    private static final String KEY_PREFIX = "ratelimit:slidingwindow:";

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    @Override
    public Mono<Boolean> tryAcquire(String key, RateLimitRule rule) {
        String redisKey = KEY_PREFIX + key;
        long now = System.currentTimeMillis();
        long windowStart = now - (rule.getWindowSize() * 1000L);
        String member = String.valueOf(now);

        return reactiveRedisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, windowStart)
                .then(reactiveRedisTemplate.opsForZSet().add(redisKey, member, now))
                .then(reactiveRedisTemplate.opsForZSet().size(redisKey))
                .map(count -> count != null && count <= rule.getPermits())
                .doOnError(e -> log.error("Sliding window rate limit error for key: {}", key, e))
                .onErrorReturn(true);
    }

    @Override
    public com.enterprise.gateway.common.enums.RateLimitStrategy getStrategyType() {
        return com.enterprise.gateway.common.enums.RateLimitStrategy.SLIDING_WINDOW;
    }
}
