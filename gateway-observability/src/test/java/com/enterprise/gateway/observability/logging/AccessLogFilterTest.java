package com.enterprise.gateway.observability.logging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@ExtendWith(MockitoExtension.class)
class AccessLogFilterTest {

    private AccessLogFilter filter;

    @Mock
    private ReactiveElasticsearchTemplate elasticsearchTemplate;

    @Mock
    private LogIndexManager logIndexManager;

    @Mock
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new AccessLogFilter(elasticsearchTemplate, logIndexManager);
    }

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(100);
    }

    @Test
    void shouldWriteAccessLogOnRequestComplete() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("192.168.1.1", 12345))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(logIndexManager.ensureIndexExists(anyString())).thenReturn(Mono.empty());
        when(elasticsearchTemplate.save(any(Map.class), anyString())).thenReturn(Mono.just(mock()));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(logIndexManager, timeout(1000)).ensureIndexExists(anyString());
        verify(elasticsearchTemplate, timeout(1000)).save(any(Map.class), anyString());
    }

    @Test
    void shouldLogAllRequiredFields() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Forwarded-For", "10.0.0.1")
                .remoteAddress(new InetSocketAddress("192.168.1.1", 12345))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(logIndexManager.ensureIndexExists(anyString())).thenReturn(Mono.empty());
        when(elasticsearchTemplate.save(any(Map.class), anyString())).thenReturn(Mono.just(mock()));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> logEntryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(elasticsearchTemplate, timeout(1000)).save(logEntryCaptor.capture(), anyString());

        Map<String, Object> logEntry = logEntryCaptor.getValue();
        assertThat(logEntry).containsKey("method");
        assertThat(logEntry).containsKey("path");
        assertThat(logEntry).containsKey("status");
        assertThat(logEntry).containsKey("duration");
        assertThat(logEntry).containsKey("clientIp");
        assertThat(logEntry).containsKey("routeId");
        assertThat(logEntry.get("method")).isEqualTo("GET");
        assertThat(logEntry.get("path")).isEqualTo("/api/test");
        assertThat(logEntry.get("status")).isEqualTo(200);
        assertThat(logEntry.get("clientIp")).isEqualTo("10.0.0.1");
        assertThat(logEntry.get("routeId")).isEqualTo("test-route");
    }

    @Test
    void shouldNotBlockRequestProcessing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("192.168.1.1", 12345))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(logIndexManager.ensureIndexExists(anyString())).thenReturn(Mono.never());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        long startTime = System.currentTimeMillis();
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
        long elapsed = System.currentTimeMillis() - startTime;

        assertThat(elapsed).isLessThan(500);
    }

    @Test
    void shouldHandleElasticsearchErrorGracefully() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("192.168.1.1", 12345))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        when(logIndexManager.ensureIndexExists(anyString())).thenReturn(Mono.empty());
        when(elasticsearchTemplate.save(any(Map.class), anyString())).thenReturn(Mono.error(new RuntimeException("ES down")));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
