package com.device.platform.gateway;

import com.device.platform.common.BusinessException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RateLimitingFilter implements WebFilter {

    @Value("${gateway.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${gateway.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${gateway.rate-limit.burst-capacity:100}")
    private int burstCapacity;

    private final Map<String, Bucket> bucketCache = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!rateLimitEnabled) {
            return chain.filter(exchange);
        }

        String clientKey = getClientKey(exchange);
        Bucket bucket = bucketCache.computeIfAbsent(clientKey, k -> createNewBucket());

        if (bucket.tryConsume(1)) {
            exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(burstCapacity));
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining",
                    String.valueOf(bucket.getAvailableTokens()));
            return chain.filter(exchange);
        } else {
            log.warn("请求被限流: clientKey={}, path={}",
                    clientKey, exchange.getRequest().getPath().value());
            throw new BusinessException(429, "请求过于频繁，请稍后重试");
        }
    }

    private Bucket createNewBucket() {
        Refill refill = Refill.intervally(requestsPerMinute, Duration.ofMinutes(1));
        Bandwidth limit = Bandwidth.classic(burstCapacity, refill);
        return Bucket4j.builder().addLimit(limit).build();
    }

    private String getClientKey(ServerWebExchange exchange) {
        String clientIp = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = exchange.getRequest().getRemoteAddress() != null ?
                    exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown";
        } else {
            clientIp = clientIp.split(",")[0].trim();
        }

        String deviceId = exchange.getRequest().getHeaders().getFirst("X-Device-Id");
        if (deviceId != null && !deviceId.isEmpty()) {
            return "device:" + deviceId;
        }

        return "ip:" + clientIp;
    }
}
