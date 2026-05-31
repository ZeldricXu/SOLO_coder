package com.iotplatform.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iotplatform.common.constant.CacheConstants;
import com.iotplatform.common.constant.ErrorCodeConstants;
import com.iotplatform.common.constant.MetricConstants;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.common.util.CacheKeyUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class RateLimitFilter implements WebFilter {

    private static final int MAX_REQUESTS_PER_MINUTE = 1000;
    private static final String UNKNOWN_IP = "unknown";

    private final MeterRegistry meterRegistry;

    private final Cache<String, AtomicInteger> rateLimitCache = Caffeine.newBuilder()
            .maximumSize(CacheConstants.RATE_LIMIT_CACHE_MAX_SIZE)
            .expireAfterWrite(Duration.ofSeconds(CacheConstants.RATE_LIMIT_SECONDS))
            .recordStats()
            .build();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String clientIp = getClientIp(exchange);
        String cacheKey = CacheKeyUtil.rateLimitKey(clientIp);

        AtomicInteger counter = rateLimitCache.get(cacheKey, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();

        if (count > MAX_REQUESTS_PER_MINUTE) {
            log.warn("Rate limit exceeded for IP: {}, count: {}", clientIp, count);
            meterRegistry.counter(MetricConstants.GATEWAY_RATELIMIT_EXCEEDED, MetricConstants.TAG_IP, clientIp)
                    .increment();
            return Mono.error(new BusinessException(ErrorCodeConstants.RATE_LIMIT_EXCEEDED, "请求过于频繁，请稍后再试"));
        }

        meterRegistry.counter(MetricConstants.GATEWAY_RATELIMIT_ALLOWED, MetricConstants.TAG_IP, clientIp)
                .increment();
        return chain.filter(exchange);
    }

    private String getClientIp(ServerWebExchange exchange) {
        if (exchange.getRequest().getRemoteAddress() == null) {
            return UNKNOWN_IP;
        }
        return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
}
