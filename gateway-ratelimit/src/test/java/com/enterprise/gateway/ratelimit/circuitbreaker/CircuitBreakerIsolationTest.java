package com.enterprise.gateway.ratelimit.circuitbreaker;

import com.enterprise.gateway.common.model.CircuitBreakerRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerIsolationTest {

    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry = new CircuitBreakerRegistry();
    }

    @Test
    void shouldIsolateStateBetweenDifferentCircuitBreakers() {
        CircuitBreakerRule ruleA = CircuitBreakerRule.builder()
                .routeId("route-a")
                .failureRateThreshold(50.0)
                .minimumNumberOfCalls(5)
                .slidingWindowSize(10)
                .status(1)
                .build();

        CircuitBreakerRule ruleB = CircuitBreakerRule.builder()
                .routeId("route-b")
                .failureRateThreshold(50.0)
                .minimumNumberOfCalls(100)
                .slidingWindowSize(100)
                .status(1)
                .build();

        io.github.resilience4j.circuitbreaker.CircuitBreaker cbA = circuitBreakerRegistry.getOrCreate("route-a", ruleA);
        io.github.resilience4j.circuitbreaker.CircuitBreaker cbB = circuitBreakerRegistry.getOrCreate("route-b", ruleB);

        for (int i = 0; i < 10; i++) {
            cbA.onError(0, new RuntimeException("fail"));
        }

        assertThat(cbA.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
        assertThat(cbB.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldCreateCircuitBreakersConcurrentlyWithoutDuplicates() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<io.github.resilience4j.circuitbreaker.CircuitBreaker> results = new ArrayList<>();

        CircuitBreakerRule sharedRule = CircuitBreakerRule.builder()
                .routeId("shared-route")
                .failureRateThreshold(50.0)
                .slidingWindowSize(100)
                .status(1)
                .build();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
                            circuitBreakerRegistry.getOrCreate("shared-route", sharedRule);
                    synchronized (results) {
                        results.add(cb);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(results).hasSize(threadCount);
        io.github.resilience4j.circuitbreaker.CircuitBreaker first = results.get(0);
        for (io.github.resilience4j.circuitbreaker.CircuitBreaker cb : results) {
            assertThat(cb).isSameAs(first);
        }
    }

    @Test
    void shouldMaintainStateIsolationWithConcurrentOperations() throws InterruptedException {
        int routeCount = 10;
        for (int i = 0; i < routeCount; i++) {
            CircuitBreakerRule rule = CircuitBreakerRule.builder()
                    .routeId("route-" + i)
                    .failureRateThreshold(50.0)
                    .minimumNumberOfCalls(i + 1)
                    .slidingWindowSize(100)
                    .status(1)
                    .build();
            circuitBreakerRegistry.getOrCreate("route-" + i, rule);
        }

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successOps = new AtomicInteger(0);
        AtomicInteger failedOps = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int routeIndex = i % routeCount;
            final boolean shouldFail = i % 3 == 0;
            executor.submit(() -> {
                try {
                    io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
                            circuitBreakerRegistry.get("route-" + routeIndex);
                    if (cb != null && cb.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED) {
                        if (shouldFail) {
                            cb.onError(0, new RuntimeException("concurrent error"));
                            failedOps.incrementAndGet();
                        } else {
                            cb.onSuccess(0);
                            successOps.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        for (int i = 0; i < routeCount; i++) {
            io.github.resilience4j.circuitbreaker.CircuitBreaker cb = circuitBreakerRegistry.get("route-" + i);
            assertThat(cb).isNotNull();
            assertThat(cb.getName()).isEqualTo("route-" + i);
        }

        assertThat(successOps.get() + failedOps.get()).isGreaterThan(0);
    }
}
