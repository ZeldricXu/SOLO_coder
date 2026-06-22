package com.enterprise.gateway.transform.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@ExtendWith(MockitoExtension.class)
class HeaderTransformFilterTest {

    private HeaderTransformFilter filter;

    @Mock
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new HeaderTransformFilter();
    }

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(-30);
    }

    @Test
    void shouldAddNewRequestHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Existing", "old")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Map<String, Object> headerTransformConfig = new HashMap<>();
        headerTransformConfig.put("add", Map.of("X-Custom", "value1"));

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .metadata(Map.of("headerTransform", headerTransformConfig))
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange exchanged = invocation.getArgument(0);
            assertThat(exchanged.getRequest().getHeaders().getFirst("X-Custom")).isEqualTo("value1");
            assertThat(exchanged.getRequest().getHeaders().getFirst("X-Existing")).isEqualTo("old");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    void shouldRemoveExistingRequestHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Old", "oldValue")
                .header("X-Keep", "keepValue")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Map<String, Object> headerTransformConfig = new HashMap<>();
        headerTransformConfig.put("remove", List.of("X-Old"));

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .metadata(Map.of("headerTransform", headerTransformConfig))
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange exchanged = invocation.getArgument(0);
            assertThat(exchanged.getRequest().getHeaders().containsKey("X-Old")).isFalse();
            assertThat(exchanged.getRequest().getHeaders().getFirst("X-Keep")).isEqualTo("keepValue");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    void shouldModifyExistingRequestHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Existing", "old")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Map<String, Object> headerTransformConfig = new HashMap<>();
        headerTransformConfig.put("modify", Map.of("X-Existing", "new"));

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .metadata(Map.of("headerTransform", headerTransformConfig))
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange exchanged = invocation.getArgument(0);
            assertThat(exchanged.getRequest().getHeaders().getFirst("X-Existing")).isEqualTo("new");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    void shouldAddResponseHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Map<String, Object> headerTransformConfig = new HashMap<>();
        headerTransformConfig.put("add", Map.of("X-Response-Custom", "responseValue"));

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .metadata(Map.of("headerTransform", headerTransformConfig))
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange exchanged = invocation.getArgument(0);
            ServerHttpResponse response = exchanged.getResponse();
            assertThat(response.getHeaders().getFirst("X-Response-Custom")).isEqualTo("responseValue");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    void shouldRemoveResponseHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerHttpResponse response = new MockServerHttpResponse();
        response.getHeaders().add("X-Old-Response", "oldValue");
        response.getHeaders().add("X-Keep-Response", "keepValue");
        MockServerWebExchange exchange = MockServerWebExchange.builder(request)
                .response(response)
                .build();

        Map<String, Object> headerTransformConfig = new HashMap<>();
        headerTransformConfig.put("remove", List.of("X-Old-Response"));

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .metadata(Map.of("headerTransform", headerTransformConfig))
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange exchanged = invocation.getArgument(0);
            HttpHeaders responseHeaders = exchanged.getResponse().getHeaders();
            assertThat(responseHeaders.containsKey("X-Old-Response")).isFalse();
            assertThat(responseHeaders.getFirst("X-Keep-Response")).isEqualTo("keepValue");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
    }
}
