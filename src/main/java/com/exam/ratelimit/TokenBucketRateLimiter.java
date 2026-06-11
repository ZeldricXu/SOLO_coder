package com.exam.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class TokenBucketRateLimiter {

    private final long capacity;
    private final long refillTokensPerSecond;
    private final AtomicLong tokens;
    private volatile long lastRefillNanos;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public TokenBucketRateLimiter() {
        this(100, 100);
    }

    public TokenBucketRateLimiter(long capacity, long refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.tokens = new AtomicLong(capacity);
        this.lastRefillNanos = System.nanoTime();
    }

    public RateLimitResult tryAcquire() {
        return tryAcquire(1);
    }

    public RateLimitResult tryAcquire(int permits) {
        refill();
        long current;
        long newTokens;
        do {
            current = tokens.get();
            if (current < permits) {
                log.warn("触发限流：令牌不足，当前={}，需要={}", current, permits);
                return RateLimitResult.rejected(tokens.get());
            }
            newTokens = current - permits;
        } while (!tokens.compareAndSet(current, newTokens));

        return RateLimitResult.allowed(newTokens);
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;

        if (elapsed <= 0) {
            return;
        }

        long secondsElapsed = elapsed / NANOS_PER_SECOND;
        if (secondsElapsed <= 0) {
            return;
        }

        long tokensToAdd = secondsElapsed * refillTokensPerSecond;
        if (tokensToAdd <= 0) {
            return;
        }

        long remainder = elapsed % NANOS_PER_SECOND;
        long expectedLast = now - remainder;

        if (lastRefillNanos < expectedLast) {
            long prev = lastRefillNanos;
            if (compareAndSetLastRefill(prev, expectedLast)) {
                long current;
                long newValue;
                do {
                    current = tokens.get();
                    newValue = Math.min(capacity, current + tokensToAdd);
                } while (!tokens.compareAndSet(current, newValue));
            }
        }
    }

    private boolean compareAndSetLastRefill(long expected, long update) {
        synchronized (this) {
            if (lastRefillNanos == expected) {
                lastRefillNanos = update;
                return true;
            }
            return false;
        }
    }

    public long getAvailableTokens() {
        refill();
        return tokens.get();
    }

    public long getCapacity() {
        return capacity;
    }

    public static class RateLimitResult {
        private final boolean allowed;
        private final long remainingTokens;
        private final long retryAfterMs;

        private RateLimitResult(boolean allowed, long remainingTokens, long retryAfterMs) {
            this.allowed = allowed;
            this.remainingTokens = remainingTokens;
            this.retryAfterMs = retryAfterMs;
        }

        public static RateLimitResult allowed(long remaining) {
            return new RateLimitResult(true, remaining, 0);
        }

        public static RateLimitResult rejected(long remaining) {
            long retry = (long) Math.ceil(1000.0 * (1 - remaining) / 100.0);
            return new RateLimitResult(false, remaining, Math.max(retry, 500));
        }

        public boolean isAllowed() {
            return allowed;
        }

        public long getRemainingTokens() {
            return remainingTokens;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }
    }
}
