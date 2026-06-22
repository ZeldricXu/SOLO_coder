package com.enterprise.gateway.ratelimit.strategy;

import com.enterprise.gateway.common.enums.RateLimitStrategy;
import com.enterprise.gateway.common.model.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlidingWindowRateLimitStrategyTest {

    @Mock
    private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    @Mock
    private ReactiveZSetOperations<String, String> reactiveZSetOperations;

    @InjectMocks
    private SlidingWindowRateLimitStrategy slidingWindowRateLimitStrategy;

    @Test
    void shouldAllowRequestUnderLimit() {
        RateLimitRule rule = RateLimitRule.builder()
                .windowSize(60L)
                .permits(100L)
                .build();
        String key = "test-key";

        when(reactiveRedisTemplate.opsForZSet()).thenReturn(reactiveZSetOperations);
        when(reactiveZSetOperations.removeRangeByScore(anyString(), anyLong(), anyLong()))
                .thenReturn(Mono.just(0L));
        when(reactiveZSetOperations.add(anyString(), anyString(), anyDouble()))
                .thenReturn(Mono.just(true));
        when(reactiveZSetOperations.size(anyString()))
                .thenReturn(Mono.just(50L));

        StepVerifier.create(slidingWindowRateLimitStrategy.tryAcquire(key, rule))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void shouldRejectRequestOverLimit() {
        RateLimitRule rule = RateLimitRule.builder()
                .windowSize(60L)
                .permits(100L)
                .build();
        String key = "test-key";

        when(reactiveRedisTemplate.opsForZSet()).thenReturn(reactiveZSetOperations);
        when(reactiveZSetOperations.removeRangeByScore(anyString(), anyLong(), anyLong()))
                .thenReturn(Mono.just(0L));
        when(reactiveZSetOperations.add(anyString(), anyString(), anyDouble()))
                .thenReturn(Mono.just(true));
        when(reactiveZSetOperations.size(anyString()))
                .thenReturn(Mono.just(150L));

        StepVerifier.create(slidingWindowRateLimitStrategy.tryAcquire(key, rule))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void shouldReturnSlidingWindowStrategyType() {
        assertThat(slidingWindowRateLimitStrategy.getStrategyType())
                .isEqualTo(RateLimitStrategy.SLIDING_WINDOW);
    }

    @Test
    void shouldGracefulDegradeOnRedisError() {
        RateLimitRule rule = RateLimitRule.builder()
                .windowSize(60L)
                .permits(100L)
                .build();
        String key = "test-key";

        when(reactiveRedisTemplate.opsForZSet()).thenReturn(reactiveZSetOperations);
        when(reactiveZSetOperations.removeRangeByScore(anyString(), anyLong(), anyLong()))
                .thenReturn(Mono.error(new RuntimeException("Redis connection error")));

        StepVerifier.create(slidingWindowRateLimitStrategy.tryAcquire(key, rule))
                .expectNext(true)
                .verifyComplete();
    }
}
