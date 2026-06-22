package com.enterprise.gateway.ratelimit.strategy;

import com.enterprise.gateway.common.model.RateLimitRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class RateLimitFallbackTest {

    @Mock
    private ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;

    private TokenBucketRateLimitStrategy tokenBucketStrategy;
    private SlidingWindowRateLimitStrategy slidingWindowStrategy;

    @BeforeEach
    void setUp() {
        tokenBucketStrategy = new TokenBucketRateLimitStrategy(reactiveRedisTemplate);
    }

    @Test
    void tokenBucketShouldFailOpenOnRedisError() {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("fallback-test")
                .strategy("TOKEN_BUCKET")
                .capacity(100L)
                .refillRate(10L)
                .build();

        when(reactiveRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        StepVerifier.create(tokenBucketStrategy.tryAcquire("test-key", rule))
                .assertNext(allowed -> assertThat(allowed).isTrue())
                .verifyComplete();
    }

    @Test
    void tokenBucketShouldAllowOnRedisTimeout() {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("timeout-test")
                .strategy("TOKEN_BUCKET")
                .capacity(100L)
                .refillRate(10L)
                .build();

        when(reactiveRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(Flux.error(new RuntimeException("Redis timeout")));

        StepVerifier.create(tokenBucketStrategy.tryAcquire("timeout-key", rule))
                .assertNext(allowed -> assertThat(allowed).isTrue())
                .verifyComplete();
    }

    @Test
    void tokenBucketShouldWorkNormallyWhenRedisHealthy() {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("healthy-test")
                .strategy("TOKEN_BUCKET")
                .capacity(100L)
                .refillRate(10L)
                .build();

        when(reactiveRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(Flux.just(1L));

        StepVerifier.create(tokenBucketStrategy.tryAcquire("healthy-key", rule))
                .assertNext(allowed -> assertThat(allowed).isTrue())
                .verifyComplete();
    }

    @Test
    void tokenBucketShouldRejectWhenFullAndRedisHealthy() {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("full-test")
                .strategy("TOKEN_BUCKET")
                .capacity(0L)
                .refillRate(0L)
                .build();

        when(reactiveRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(Flux.just(0L));

        StepVerifier.create(tokenBucketStrategy.tryAcquire("full-key", rule))
                .assertNext(allowed -> assertThat(allowed).isFalse())
                .verifyComplete();
    }
}
