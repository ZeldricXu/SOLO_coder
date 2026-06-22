package com.enterprise.gateway.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@ExtendWith(MockitoExtension.class)
class DistributedTracingFilterTest {

    private DistributedTracingFilter filter;

    @Mock
    private Tracer tracer;

    @Mock
    private TraceContextPropagator traceContextPropagator;

    @Mock
    private GatewayFilterChain chain;

    @Mock
    private Span span;

    @Mock
    private Span.Builder spanBuilder;

    @Mock
    private TraceContext traceContext;

    @Mock
    private Tracer.SpanInScope spanInScope;

    @BeforeEach
    void setUp() {
        filter = new DistributedTracingFilter(tracer, traceContextPropagator);
    }

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(1);
    }

    private void setupCommonMocks(ServerWebExchange exchange) {
        when(tracer.currentSpan()).thenReturn(null);
        doReturn(spanBuilder).when(tracer).nextSpan(any(Span.class));
        when(spanBuilder.name("gateway.request")).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(spanInScope);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("trace-123");
        when(traceContext.spanId()).thenReturn("span-456");
        when(chain.filter(exchange)).thenReturn(Mono.empty());
    }

    @Test
    void shouldCreateGatewayRequestSpan() {
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

        setupCommonMocks(exchange);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(spanBuilder).name("gateway.request");
        verify(spanBuilder).start();
        verify(span).end();
        verify(spanInScope).close();
    }

    @Test
    void shouldSetTraceIdInResponseHeaders() {
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

        setupCommonMocks(exchange);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        assertThat(responseHeaders.getFirst("X-Trace-Id")).isEqualTo("trace-123");
    }

    @Test
    void shouldSetSpanIdInResponseHeaders() {
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

        setupCommonMocks(exchange);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        assertThat(responseHeaders.getFirst("X-Span-Id")).isEqualTo("span-456");
    }

    @Test
    void shouldAddHttpTagsToSpan() {
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

        setupCommonMocks(exchange);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(span).tag("http.method", "GET");
        verify(span).tag("http.path", "/api/test");
    }
}
