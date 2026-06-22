package com.enterprise.gateway.ratelimit.circuitbreaker;

import com.enterprise.gateway.common.model.CircuitBreakerRule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerRegistryTest {

    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry = new CircuitBreakerRegistry();
    }

    @Test
    void shouldCreateCircuitBreakerForRoute() {
        CircuitBreakerRule rule = CircuitBreakerRule.builder()
                .routeId("test-route")
                .failureRateThreshold(50.0)
                .slidingWindowSize(100)
                .build();

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.getOrCreate("test-route", rule);

        assertThat(circuitBreaker).isNotNull();
        assertThat(circuitBreaker.getName()).isEqualTo("test-route");
    }

    @Test
    void shouldReturnSameInstanceForSameRoute() {
        CircuitBreakerRule rule = CircuitBreakerRule.builder()
                .routeId("test-route")
                .failureRateThreshold(50.0)
                .slidingWindowSize(100)
                .build();

        CircuitBreaker first = circuitBreakerRegistry.getOrCreate("test-route", rule);
        CircuitBreaker second = circuitBreakerRegistry.getOrCreate("test-route", rule);

        assertThat(first).isSameAs(second);
    }

    @Test
    void shouldRemoveCircuitBreaker() {
        CircuitBreakerRule rule = CircuitBreakerRule.builder()
                .routeId("test-route")
                .failureRateThreshold(50.0)
                .slidingWindowSize(100)
                .build();

        circuitBreakerRegistry.getOrCreate("test-route", rule);
        assertThat(circuitBreakerRegistry.get("test-route")).isNotNull();

        circuitBreakerRegistry.remove("test-route");
        assertThat(circuitBreakerRegistry.get("test-route")).isNull();
    }

    @Test
    void shouldRefreshAllCircuitBreakers() {
        CircuitBreakerRule rule1 = CircuitBreakerRule.builder()
                .routeId("route-1")
                .failureRateThreshold(50.0)
                .slidingWindowSize(100)
                .status(1)
                .build();
        CircuitBreakerRule rule2 = CircuitBreakerRule.builder()
                .routeId("route-2")
                .failureRateThreshold(60.0)
                .slidingWindowSize(200)
                .status(1)
                .build();
        CircuitBreakerRule disabledRule = CircuitBreakerRule.builder()
                .routeId("route-3")
                .failureRateThreshold(50.0)
                .slidingWindowSize(100)
                .status(0)
                .build();

        circuitBreakerRegistry.getOrCreate("old-route", CircuitBreakerRule.builder()
                .routeId("old-route")
                .failureRateThreshold(50.0)
                .slidingWindowSize(100)
                .build());
        assertThat(circuitBreakerRegistry.get("old-route")).isNotNull();

        List<CircuitBreakerRule> rules = Arrays.asList(rule1, rule2, disabledRule);
        circuitBreakerRegistry.refreshAll(rules);

        assertThat(circuitBreakerRegistry.get("old-route")).isNull();
        assertThat(circuitBreakerRegistry.get("route-1")).isNotNull();
        assertThat(circuitBreakerRegistry.get("route-2")).isNotNull();
        assertThat(circuitBreakerRegistry.get("route-3")).isNull();
    }
}
