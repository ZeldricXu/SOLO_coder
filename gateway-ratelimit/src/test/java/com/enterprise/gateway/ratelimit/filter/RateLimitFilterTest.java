package com.enterprise.gateway.ratelimit.filter;

import com.enterprise.gateway.common.enums.RateLimitStrategy;
import com.enterprise.gateway.common.model.RateLimitRule;
import com.enterprise.gateway.ratelimit.strategy.RateLimitStrategyFactory;
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

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private RateLimitStrategyFactory strategyFactory;

    @Mock
    private com.enterprise.gateway.ratelimit.strategy.RateLimitStrategy rateLimitStrategy;

    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void setUp() {
        rateLimitFilter = new RateLimitFilter(strategyFactory);
    }

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(rateLimitFilter.getOrder()).isEqualTo(-50);
    }

    @Test
    void shouldPassThroughWhenNoRoute() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(rateLimitFilter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void shouldPassThroughWhenNoRule() {
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
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(rateLimitFilter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void shouldAllowRequestWithinLimit() {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("test-route")
                .strategy("TOKEN_BUCKET")
                .capacity(100L)
                .status(1)
                .build();
        rateLimitFilter.updateRule(rule);

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
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        when(strategyFactory.getStrategy(RateLimitStrategy.TOKEN_BUCKET)).thenReturn(rateLimitStrategy);
        when(rateLimitStrategy.tryAcquire(anyString(), any(RateLimitRule.class))).thenReturn(Mono.just(true));

        StepVerifier.create(rateLimitFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("100");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("99");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Reset")).isNotNull();
    }

    @Test
    void shouldRejectRequestOverLimit() {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("test-route")
                .strategy("TOKEN_BUCKET")
                .capacity(100L)
                .status(1)
                .build();
        rateLimitFilter.updateRule(rule);

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

        when(strategyFactory.getStrategy(RateLimitStrategy.TOKEN_BUCKET)).thenReturn(rateLimitStrategy);
        when(rateLimitStrategy.tryAcquire(anyString(), any(RateLimitRule.class))).thenReturn(Mono.just(false));

        StepVerifier.create(rateLimitFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void shouldExtractUserIdAsKey() {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("test-route")
                .strategy("TOKEN_BUCKET")
                .capacity(100L)
                .status(1)
                .build();
        rateLimitFilter.updateRule(rule);

        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .header("X-User-Id", "user123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        when(strategyFactory.getStrategy(RateLimitStrategy.TOKEN_BUCKET)).thenReturn(rateLimitStrategy);
        when(rateLimitStrategy.tryAcquire(anyString(), any(RateLimitRule.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            assertThat(key).startsWith("user:");
            return Mono.just(true);
        });

        StepVerifier.create(rateLimitFilter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void shouldExtractIpAsKey() {
        RateLimitRule rule = RateLimitRule.builder()
                .routeId("test-route")
                .strategy("TOKEN_BUCKET")
                .capacity(100L)
                .status(1)
                .build();
        rateLimitFilter.updateRule(rule);

        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .remoteAddress(new InetSocketAddress("192.168.1.1", 54321))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        when(strategyFactory.getStrategy(RateLimitStrategy.TOKEN_BUCKET)).thenReturn(rateLimitStrategy);
        when(rateLimitStrategy.tryAcquire(anyString(), any(RateLimitRule.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            assertThat(key).startsWith("ip:");
            return Mono.just(true);
        });

        StepVerifier.create(rateLimitFilter.filter(exchange, chain))
                .verifyComplete();
    }
}
