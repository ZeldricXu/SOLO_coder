package com.enterprise.gateway.ratelimit.circuitbreaker;

import com.enterprise.gateway.common.model.CircuitBreakerRule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerFilterTest {

    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Mock
    private CircuitBreakerStateListener stateListener;

    private CircuitBreakerFilter circuitBreakerFilter;

    @BeforeEach
    void setUp() {
        circuitBreakerFilter = new CircuitBreakerFilter(circuitBreakerRegistry, stateListener);
    }

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(circuitBreakerFilter.getOrder()).isEqualTo(-40);
    }

    @Test
    void shouldPassThroughWhenNoRoute() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(circuitBreakerFilter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void shouldReturn503WhenCircuitBreakerOpen() {
        CircuitBreakerRule rule = CircuitBreakerRule.builder()
                .routeId("test-route")
                .failureRateThreshold(50.0)
                .slidingWindowSize(100)
                .status(1)
                .build();
        circuitBreakerFilter.updateRule(rule);

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(100)
                .build();
        CircuitBreaker circuitBreaker = CircuitBreaker.of("test-route", config);
        circuitBreaker.transitionToOpenState();

        when(circuitBreakerRegistry.getOrCreate("test-route", rule)).thenReturn(circuitBreaker);

        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(circuitBreakerFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
