package com.enterprise.gateway.ratelimit.strategy;

import com.enterprise.gateway.common.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBucketRateLimitStrategy implements RateLimitStrategy {

    private static final String KEY_PREFIX = "ratelimit:tokenbucket:";

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            
            local data = redis.call('HMGET', key, 'tokens', 'lastRefill')
            local tokens = tonumber(data[1])
            local lastRefill = tonumber(data[2])
            
            if tokens == nil then
                tokens = capacity
                lastRefill = now
            end
            
            local elapsed = now - lastRefill
            local tokensToAdd = elapsed * refillRate
            tokens = math.min(capacity, tokens + tokensToAdd)
            lastRefill = now
            
            if tokens >= 1 then
                tokens = tokens - 1
                redis.call('HMSET', key, 'tokens', tokens, 'lastRefill', lastRefill)
                redis.call('EXPIRE', key, 60)
                return 1
            else
                redis.call('HMSET', key, 'tokens', tokens, 'lastRefill', lastRefill)
                redis.call('EXPIRE', key, 60)
                return 0
            end
            """;

    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;
    private final RedisScript<Long> tokenBucketScript = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    @Override
    public Mono<Boolean> tryAcquire(String key, RateLimitRule rule) {
        String redisKey = KEY_PREFIX + key;
        long now = System.currentTimeMillis() / 1000L;
        List<String> keys = Collections.singletonList(redisKey);

        return reactiveRedisTemplate.execute(tokenBucketScript, keys,
                        String.valueOf(rule.getCapacity()),
                        String.valueOf(rule.getRefillRate()),
                        String.valueOf(now))
                .map(result -> result != null && result == 1L)
                .doOnError(e -> log.error("Token bucket rate limit error for key: {}", key, e))
                .onErrorReturn(true);
    }

    @Override
    public com.enterprise.gateway.common.enums.RateLimitStrategy getStrategyType() {
        return com.enterprise.gateway.common.enums.RateLimitStrategy.TOKEN_BUCKET;
    }
}
