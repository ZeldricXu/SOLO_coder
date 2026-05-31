package com.dynamiclog.gateway.service;

import com.bucket4j.core.Bandwidth;
import com.bucket4j.core.Bucket;
import com.bucket4j.core.Bucket4j;
import com.bucket4j.core.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RateLimitingService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, RateLimitConfig> configs = new ConcurrentHashMap<>();

    public Mono<Boolean> tryConsume(String key, int tokens) {
        return Mono.fromCallable(() -> {
            Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket(k));
            return bucket.tryConsume(tokens);
        });
    }

    public Mono<RateLimitStatus> getStatus(String key) {
        return Mono.fromCallable(() -> {
            Bucket bucket = buckets.get(key);
            if (bucket == null) {
                bucket = createBucket(key);
            }
            long available = bucket.getAvailableTokens();
            RateLimitConfig config = configs.getOrDefault(key, getDefaultConfig());
            return new RateLimitStatus(available, config.getCapacity(), config.getRefillTokens(), config.getRefillDuration());
        });
    }

    public void configureRateLimit(String key, long capacity, long refillTokens, Duration refillDuration) {
        RateLimitConfig config = new RateLimitConfig(capacity, refillTokens, refillDuration);
        configs.put(key, config);
        buckets.remove(key);
        log.info("Rate limit configured: key={}, capacity={}, refill={}/{}", key, capacity, refillTokens, refillDuration);
    }

    private Bucket createBucket(String key) {
        RateLimitConfig config = configs.getOrDefault(key, getDefaultConfig());
        Bandwidth bandwidth = Bandwidth.classic(
                config.getCapacity(),
                Refill.intervally(config.getRefillTokens(), config.getRefillDuration())
        );
        return Bucket4j.builder().addLimit(bandwidth).build();
    }

    private RateLimitConfig getDefaultConfig() {
        return new RateLimitConfig(100, 10, Duration.ofSeconds(1));
    }

    public static class RateLimitConfig {
        private final long capacity;
        private final long refillTokens;
        private final Duration refillDuration;

        public RateLimitConfig(long capacity, long refillTokens, Duration refillDuration) {
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.refillDuration = refillDuration;
        }

        public long getCapacity() { return capacity; }
        public long getRefillTokens() { return refillTokens; }
        public Duration getRefillDuration() { return refillDuration; }
    }

    public record RateLimitStatus(long available, long capacity, long refillTokens, Duration refillInterval) {}
}
