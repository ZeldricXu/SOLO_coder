package com.enterprise.gateway.ratelimit.strategy;

import com.enterprise.gateway.common.enums.RateLimitStrategy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RateLimitStrategyFactory {

    private final List<com.enterprise.gateway.ratelimit.strategy.RateLimitStrategy> strategies;
    private final Map<RateLimitStrategy, com.enterprise.gateway.ratelimit.strategy.RateLimitStrategy> strategyMap = new EnumMap<>(RateLimitStrategy.class);

    @PostConstruct
    public void init() {
        for (com.enterprise.gateway.ratelimit.strategy.RateLimitStrategy strategy : strategies) {
            strategyMap.put(strategy.getStrategyType(), strategy);
        }
    }

    public com.enterprise.gateway.ratelimit.strategy.RateLimitStrategy getStrategy(RateLimitStrategy type) {
        return strategyMap.get(type);
    }
}
