package com.enterprise.gateway.routing.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@ExtendWith(MockitoExtension.class)
class WeightedRouteFilterTest {

    private WeightedRouteFilter filter;

    @Mock
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new WeightedRouteFilter();
    }

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(0);
    }

    @Test
    void shouldPassThroughWhenNoWeightConfig() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        Route route = Route.async()
                .id("test-route")
                .uri("http://localhost:8080")
                .predicate(ex -> true)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        assertThat(exchange.getAttributes()).doesNotContainKey("selectedBackend");
    }

    @Test
    void shouldSelectBackendByWeight() {
        Map<String, Integer> weights = new HashMap<>();
        weights.put("v1", 70);
        weights.put("v2", 30);

        int v1Count = 0;
        int v2Count = 0;
        int iterations = 1000;

        for (int i = 0; i < iterations; i++) {
            String backend = filter.selectByWeight(weights);
            if ("v1".equals(backend)) {
                v1Count++;
            } else if ("v2".equals(backend)) {
                v2Count++;
            }
        }

        assertThat(v1Count).isBetween(650, 750);
        assertThat(v2Count).isBetween(250, 350);
        assertThat(v1Count + v2Count).isEqualTo(iterations);
    }

    @Test
    void shouldHandleSingleBackend() {
        Map<String, Integer> weights = new HashMap<>();
        weights.put("v1", 100);

        for (int i = 0; i < 100; i++) {
            assertThat(filter.selectByWeight(weights)).isEqualTo("v1");
        }
    }
}
