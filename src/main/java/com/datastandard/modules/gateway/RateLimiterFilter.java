package com.datastandard.modules.gateway;

import cn.hutool.core.util.StrUtil;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimiterFilter implements WebFilter {

    private static final int MAX_BUCKETS = 10000;

    @Value("${gateway.rate-limiter.enabled:true}")
    private boolean enabled;

    @Value("${gateway.rate-limiter.requests-per-second:100}")
    private double requestsPerSecond;

    @Value("${gateway.rate-limiter.burst-capacity:200}")
    private int burstCapacity;

    @Value("#{'${gateway.rate-limiter.ip-whitelist:}'.split(',')}")
    private List<String> ipWhitelist = new ArrayList<>();

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Set<String> whitelistSet = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String clientIp = getClientIp(request);

        if (isWhitelisted(clientIp)) {
            return chain.filter(exchange);
        }

        String key = getRateLimitKey(request, clientIp);
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(requestsPerSecond, burstCapacity));

        cleanOldBuckets();

        if (bucket.tryAcquire()) {
            return chain.filter(exchange);
        } else {
            log.warn("Rate limit exceeded for IP: {}, key: {}", clientIp, key);
            exchange.getAttributes().put("rateLimited", true);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().add("X-RateLimit-Retry-After",
                    String.valueOf(bucket.getNanosToNextToken() / 1_000_000_000));
            return exchange.getResponse().setComplete();
        }
    }

    private String getRateLimitKey(ServerHttpRequest request, String clientIp) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (StrUtil.isNotBlank(userId)) {
            return "user:" + userId;
        }
        return "ip:" + clientIp;
    }

    private String getClientIp(ServerHttpRequest request) {
        String ip = request.getHeaders().getFirst("X-Forwarded-For");
        if (StrUtil.isNotBlank(ip) && !"unknown".equalsIgnoreCase(ip)) {
            int index = ip.indexOf(',');
            if (index != -1) {
                return ip.substring(0, index).trim();
            }
            return ip.trim();
        }
        ip = request.getHeaders().getFirst("X-Real-IP");
        if (StrUtil.isNotBlank(ip) && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddress() != null ?
                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }

    private boolean isWhitelisted(String clientIp) {
        if (whitelistSet.isEmpty()) {
            for (String ip : ipWhitelist) {
                if (StrUtil.isNotBlank(ip)) {
                    whitelistSet.add(ip.trim());
                }
            }
        }
        return whitelistSet.contains(clientIp);
    }

    private void cleanOldBuckets() {
        if (buckets.size() > MAX_BUCKETS) {
            long now = System.nanoTime();
            buckets.entrySet().removeIf(entry ->
                    now - entry.getValue().getLastAccessTime() > Duration.ofHours(1).toNanos());
        }
    }

    private static class TokenBucket {
        private final double refillRate;
        private final double capacity;
        private final AtomicLong tokens;
        private final AtomicLong lastRefillTime;
        private final AtomicLong lastAccessTime;

        TokenBucket(double requestsPerSecond, int burstCapacity) {
            this.refillRate = requestsPerSecond;
            this.capacity = burstCapacity;
            this.tokens = new AtomicLong(burstCapacity * 1_000_000_000L);
            long now = System.nanoTime();
            this.lastRefillTime = new AtomicLong(now);
            this.lastAccessTime = new AtomicLong(now);
        }

        boolean tryAcquire() {
            lastAccessTime.set(System.nanoTime());
            refillTokens();
            if (tokens.get() >= 1_000_000_000L) {
                tokens.addAndGet(-1_000_000_000L);
                return true;
            }
            return false;
        }

        private void refillTokens() {
            long now = System.nanoTime();
            long lastTime = lastRefillTime.get();
            if (now > lastTime) {
                if (lastRefillTime.compareAndSet(lastTime, now)) {
                    long nanosElapsed = now - lastTime;
                    long newTokens = (long) (nanosElapsed * refillRate);
                    long currentTokens = tokens.get();
                    tokens.set(Math.min((long) (capacity * 1_000_000_000L), currentTokens + newTokens));
                }
            }
        }

        long getNanosToNextToken() {
            long currentTokens = tokens.get();
            if (currentTokens >= 1_000_000_000L) {
                return 0;
            }
            long deficit = 1_000_000_000L - currentTokens;
            return (long) (deficit / refillRate);
        }

        long getLastAccessTime() {
            return lastAccessTime.get();
        }
    }
}
