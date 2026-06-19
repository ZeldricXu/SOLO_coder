package com.enterprise.gateway.ratelimit.circuitbreaker;

import com.enterprise.gateway.common.model.CircuitBreakerRule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CircuitBreakerRegistry {

    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public CircuitBreaker getOrCreate(String routeId, CircuitBreakerRule rule) {
        return circuitBreakers.computeIfAbsent(routeId, k -> createCircuitBreaker(routeId, rule));
    }

    public void remove(String routeId) {
        circuitBreakers.remove(routeId);
        log.info("Circuit breaker removed for route: {}", routeId);
    }

    public void refreshAll(List<CircuitBreakerRule> rules) {
        circuitBreakers.clear();
        for (CircuitBreakerRule rule : rules) {
            if (rule.getStatus() != null && rule.getStatus() == 1) {
                getOrCreate(rule.getRouteId(), rule);
            }
        }
        log.info("Refreshed {} circuit breakers", circuitBreakers.size());
    }

    public CircuitBreaker get(String routeId) {
        return circuitBreakers.get(routeId);
    }

    private CircuitBreaker createCircuitBreaker(String routeId, CircuitBreakerRule rule) {
        CircuitBreakerConfig config = buildConfig(rule);
        CircuitBreaker circuitBreaker = CircuitBreaker.of(routeId, config);
        log.info("Created circuit breaker for route: {}, config: {}", routeId, config);
        return circuitBreaker;
    }

    private CircuitBreakerConfig buildConfig(CircuitBreakerRule rule) {
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom();

        if (rule.getFailureRateThreshold() != null) {
            builder.failureRateThreshold(rule.getFailureRateThreshold());
        }

        if (rule.getSlowCallRateThreshold() != null) {
            builder.slowCallRateThreshold(rule.getSlowCallRateThreshold());
        }

        if (rule.getSlowCallDurationThreshold() != null) {
            builder.slowCallDurationThreshold(Duration.ofMillis(rule.getSlowCallDurationThreshold()));
        }

        if (rule.getWaitDurationInOpenState() != null) {
            builder.waitDurationInOpenState(Duration.ofMillis(rule.getWaitDurationInOpenState()));
        }

        if (rule.getPermittedNumberOfCallsInHalfOpenState() != null) {
            builder.permittedNumberOfCallsInHalfOpenState(rule.getPermittedNumberOfCallsInHalfOpenState());
        }

        if (rule.getMinimumNumberOfCalls() != null) {
            builder.minimumNumberOfCalls(rule.getMinimumNumberOfCalls());
        }

        if (rule.getSlidingWindowSize() != null) {
            builder.slidingWindowSize(rule.getSlidingWindowSize());
        }

        return builder.build();
    }
}
