package com.enterprise.gateway.ratelimit.strategy;

import com.enterprise.gateway.common.enums.RateLimitStrategy;
import com.enterprise.gateway.common.model.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenBucketRateLimitStrategyTest {

    @Mock
    private ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;

    @InjectMocks
    private TokenBucketRateLimitStrategy tokenBucketRateLimitStrategy;

    @Test
    void shouldAllowRequestWhenTokensAvailable() {
        RateLimitRule rule = RateLimitRule.builder()
                .capacity(100L)
                .refillRate(10L)
                .build();
        String key = "test-key";

        when(reactiveRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.just(1L));

        StepVerifier.create(tokenBucketRateLimitStrategy.tryAcquire(key, rule))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void shouldRejectRequestWhenBucketFull() {
        RateLimitRule rule = RateLimitRule.builder()
                .capacity(100L)
                .refillRate(10L)
                .build();
        String key = "test-key";

        when(reactiveRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.just(0L));

        StepVerifier.create(tokenBucketRateLimitStrategy.tryAcquire(key, rule))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldReturnTokenBucketStrategyType() {
        assertThat(tokenBucketRateLimitStrategy.getStrategyType())
                .isEqualTo(RateLimitStrategy.TOKEN_BUCKET);
    }

    @Test
    void shouldGracefulDegradeOnRedisError() {
        RateLimitRule rule = RateLimitRule.builder()
                .capacity(100L)
                .refillRate(10L)
                .build();
        String key = "test-key";

        when(reactiveRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(Flux.error(new RuntimeException("Redis connection error")));

        StepVerifier.create(tokenBucketRateLimitStrategy.tryAcquire(key, rule))
                .expectNext(true)
                .verifyComplete();
    }
}
