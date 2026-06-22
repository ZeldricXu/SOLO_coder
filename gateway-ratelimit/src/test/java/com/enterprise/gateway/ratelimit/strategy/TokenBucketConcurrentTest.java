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
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenBucketConcurrentTest {

    @Mock
    private ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;

    private TokenBucketRateLimitStrategy strategy;

    private AtomicInteger tokensRemaining;
    private final int initialCapacity = 100;

    @BeforeEach
    void setUp() {
        strategy = new TokenBucketRateLimitStrategy(reactiveRedisTemplate);
        tokensRemaining = new AtomicInteger(initialCapacity);
    }

    @Test
    void shouldMaintainAtomicityUnderHighConcurrency() throws InterruptedException {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("test-route")
                .strategy("TOKEN_BUCKET")
                .capacity((long) initialCapacity)
                .refillRate(10L)
                .build();

        when(reactiveRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(invocation -> {
                    int remaining = tokensRemaining.decrementAndGet();
                    if (remaining >= 0) {
                        return Flux.just(1L);
                    } else {
                        tokensRemaining.incrementAndGet();
                        return Flux.just(0L);
                    }
                });

        int threadCount = 50;
        int requestsPerThread = 5;
        int totalRequests = threadCount * requestsPerThread;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    Boolean allowed = strategy.tryAcquire("concurrent-key", rule)
                            .subscribeOn(Schedulers.parallel())
                            .block(Duration.ofSeconds(5));
                    if (Boolean.TRUE.equals(allowed)) {
                        successCount.incrementAndGet();
                    } else {
                        rejectCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(initialCapacity);
        assertThat(rejectCount.get()).isEqualTo(totalRequests - initialCapacity);
        assertThat(successCount.get() + rejectCount.get()).isEqualTo(totalRequests);
    }

    @Test
    void shouldNotExceedCapacityUnderConcurrentBurst() throws InterruptedException {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("burst-test")
                .strategy("TOKEN_BUCKET")
                .capacity(50L)
                .refillRate(0L)
                .build();

        when(reactiveRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(invocation -> {
                    int remaining = tokensRemaining.getAndUpdate(t -> t > 0 ? t - 1 : 0);
                    if (remaining > 0) {
                        return Flux.just(1L);
                    } else {
                        return Flux.just(0L);
                    }
                });

        int totalThreads = 30;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);
        List<Boolean> results = new ArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 10; j++) {
                        Boolean allowed = strategy.tryAcquire("burst-key", rule)
                                .block(Duration.ofSeconds(3));
                        synchronized (results) {
                            results.add(allowed);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        long successCount = results.stream().filter(b -> b).count();
        assertThat(successCount).isLessThanOrEqualTo(50);
        assertThat(results).hasSize(300);
    }
}
