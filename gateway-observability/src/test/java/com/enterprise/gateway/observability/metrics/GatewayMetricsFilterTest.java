package com.enterprise.gateway.observability.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@ExtendWith(MockitoExtension.class)
class GatewayMetricsFilterTest {

    private GatewayMetricsFilter filter;
    private SimpleMeterRegistry meterRegistry;

    @Mock
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new GatewayMetricsFilter(meterRegistry);
    }

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(50);
    }

    @Test
    void shouldRecordRequestCount() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        double count = meterRegistry.get("gateway.requests.count")
                .tag("routeId", "test-route")
                .tag("method", "GET")
                .tag("status", "200")
                .tag("uri", "/api/test")
                .counter()
                .count();

        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void shouldRecordRequestLatency() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        Meter timer = meterRegistry.get("gateway.requests.latency")
                .tag("routeId", "test-route")
                .tag("method", "GET")
                .tag("status", "200")
                .tag("uri", "/api/test")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.getId().getName()).isEqualTo("gateway.requests.latency");
    }

    @Test
    void shouldRecordErrorCount() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        double errorCount = meterRegistry.get("gateway.requests.errors")
                .tag("routeId", "test-route")
                .tag("method", "GET")
                .tag("errorType", "HTTP_500")
                .counter()
                .count();

        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    void shouldRecordSuccessStatus() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        double count = meterRegistry.get("gateway.requests.count")
                .tag("routeId", "test-route")
                .tag("method", "GET")
                .tag("status", "200")
                .tag("uri", "/api/test")
                .counter()
                .count();

        assertThat(count).isEqualTo(1.0);
        assertThat(meterRegistry.find("gateway.requests.errors").counter()).isNull();
    }

    @Test
    void shouldNotRecordMetricsForMissingRoute() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        double count = meterRegistry.get("gateway.requests.count")
                .tag("routeId", "unknown")
                .tag("method", "GET")
                .tag("status", "200")
                .tag("uri", "/api/test")
                .counter()
                .count();

        assertThat(count).isEqualTo(1.0);
    }
}
