package com.datastandard.modules.gateway;

import com.datastandard.modules.gateway.dto.CircuitBreakerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class CircuitBreakerFilter implements WebFilter {

    @Value("${gateway.circuit-breaker.enabled:true}")
    private boolean enabled;

    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.builder()
            .name("default")
            .failureThreshold(50)
            .slowCallThreshold(100)
            .slowCallDuration(Duration.ofSeconds(2))
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(10)
            .slidingWindowSize(100)
            .minimumNumberOfCalls(20)
            .build();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String key = getCircuitBreakerKey(request);
        CircuitBreaker circuitBreaker = circuitBreakers.computeIfAbsent(key, k -> new CircuitBreaker(defaultConfig));

        CircuitBreaker.State state = circuitBreaker.getState();
        if (state == CircuitBreaker.State.OPEN) {
            if (!circuitBreaker.tryAcquirePermission()) {
                log.warn("Circuit breaker is OPEN for key: {}", key);
                exchange.getAttributes().put("circuitOpen", true);
                exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                exchange.getResponse().getHeaders().add("X-Circuit-Breaker", "OPEN");
                exchange.getResponse().getHeaders().add("Retry-After",
                        String.valueOf(circuitBreaker.getRemainingWaitTime().getSeconds()));
                return exchange.getResponse().setComplete();
            }
            state = CircuitBreaker.State.HALF_OPEN;
        }

        final CircuitBreaker.State finalState = state;
        long startTime = System.nanoTime();

        return chain.filter(exchange)
                .doOnSuccess(v -> {
                    long durationMs = Duration.ofNanos(System.nanoTime() - startTime).toMillis();
                    boolean isSlowCall = durationMs > defaultConfig.getSlowCallDuration().toMillis();
                    circuitBreaker.recordSuccess(durationMs, isSlowCall, finalState);
                })
                .doOnError(e -> {
                    long durationMs = Duration.ofNanos(System.nanoTime() - startTime).toMillis();
                    boolean isSlowCall = durationMs > defaultConfig.getSlowCallDuration().toMillis();
                    circuitBreaker.recordFailure(durationMs, isSlowCall, finalState);
                });
    }

    private String getCircuitBreakerKey(ServerHttpRequest request) {
        return "path:" + request.getPath().value();
    }

    static class CircuitBreaker {
        enum State {
            CLOSED, OPEN, HALF_OPEN
        }

        private final CircuitBreakerConfig config;
        private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        private final AtomicLong openedAt = new AtomicLong(0);

        private final AtomicInteger[] slidingWindow;
        private final AtomicLong windowHead = new AtomicLong(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger slowCallCount = new AtomicInteger(0);
        private final AtomicInteger totalCalls = new AtomicInteger(0);
        private final AtomicInteger halfOpenCalls = new AtomicInteger(0);
        private final AtomicInteger halfOpenSuccess = new AtomicInteger(0);
        private final AtomicInteger halfOpenFailure = new AtomicInteger(0);

        @SuppressWarnings("unchecked")
        CircuitBreaker(CircuitBreakerConfig config) {
            this.config = config;
            this.slidingWindow = new AtomicInteger[config.getSlidingWindowSize()];
            for (int i = 0; i < config.getSlidingWindowSize(); i++) {
                this.slidingWindow[i] = new AtomicInteger(0);
            }
        }

        State getState() {
            State currentState = state.get();
            if (currentState == State.OPEN) {
                long waitTime = config.getWaitDurationInOpenState().toNanos();
                if (System.nanoTime() - openedAt.get() > waitTime) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenCalls.set(0);
                        halfOpenSuccess.set(0);
                        halfOpenFailure.set(0);
                        log.info("Circuit breaker transitioning to HALF_OPEN");
                    }
                    return State.HALF_OPEN;
                }
            }
            return currentState;
        }

        boolean tryAcquirePermission() {
            if (state.get() == State.HALF_OPEN) {
                int currentCalls = halfOpenCalls.incrementAndGet();
                return currentCalls <= config.getPermittedNumberOfCallsInHalfOpenState();
            }
            return true;
        }

        Duration getRemainingWaitTime() {
            long remaining = config.getWaitDurationInOpenState().toNanos() - (System.nanoTime() - openedAt.get());
            return Duration.ofNanos(Math.max(0, remaining));
        }

        void recordSuccess(long durationMs, boolean isSlowCall, State currentState) {
            recordResult(1, durationMs, isSlowCall, currentState);
        }

        void recordFailure(long durationMs, boolean isSlowCall, State currentState) {
            recordResult(-1, durationMs, isSlowCall, currentState);
        }

        private synchronized void recordResult(int result, long durationMs, boolean isSlowCall, State currentState) {
            if (currentState == State.HALF_OPEN) {
                if (result == 1) {
                    halfOpenSuccess.incrementAndGet();
                } else {
                    halfOpenFailure.incrementAndGet();
                }

                int total = halfOpenSuccess.get() + halfOpenFailure.get();
                if (total >= config.getPermittedNumberOfCallsInHalfOpenState()) {
                    int failureRate = (int) (halfOpenFailure.get() * 100.0 / total);
                    if (failureRate >= config.getFailureThreshold()) {
                        transitionToOpen();
                    } else {
                        transitionToClosed();
                    }
                }
                return;
            }

            int windowSize = config.getSlidingWindowSize();
            long head = windowHead.getAndIncrement();
            int index = (int) (head % windowSize);
            int oldValue = slidingWindow[index].getAndSet(result);

            totalCalls.incrementAndGet();
            if (oldValue == 1) successCount.decrementAndGet();
            if (oldValue == -1) failureCount.decrementAndGet();
            if (result == 1) successCount.incrementAndGet();
            if (result == -1) failureCount.incrementAndGet();
            if (isSlowCall) slowCallCount.incrementAndGet();

            if (totalCalls.get() >= config.getMinimumNumberOfCalls()) {
                int total = successCount.get() + failureCount.get();
                int failureRate = total > 0 ? (int) (failureCount.get() * 100.0 / total) : 0;
                int slowRate = total > 0 ? (int) (slowCallCount.get() * 100.0 / total) : 0;

                if (failureRate >= config.getFailureThreshold() || slowRate >= config.getSlowCallThreshold()) {
                    transitionToOpen();
                }
            }
        }

        private void transitionToOpen() {
            if (state.compareAndSet(State.CLOSED, State.OPEN) ||
                    state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(System.nanoTime());
                log.warn("Circuit breaker transitioned to OPEN state");
            }
        }

        private void transitionToClosed() {
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                resetCounters();
                log.info("Circuit breaker transitioned to CLOSED state");
            }
        }

        private void resetCounters() {
            successCount.set(0);
            failureCount.set(0);
            slowCallCount.set(0);
            totalCalls.set(0);
            for (AtomicInteger slot : slidingWindow) {
                slot.set(0);
            }
        }
    }
}
