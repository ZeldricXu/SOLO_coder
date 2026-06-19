package com.enterprise.gateway.ratelimit.strategy;

import com.enterprise.gateway.common.model.RateLimitRule;
import reactor.core.publisher.Mono;

public interface RateLimitStrategy {

    Mono<Boolean> tryAcquire(String key, RateLimitRule rule);

    com.enterprise.gateway.common.enums.RateLimitStrategy getStrategyType();
}
