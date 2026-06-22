package com.enterprise.gateway.auth.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IpFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private GatewayFilterChain chain;

    @InjectMocks
    private IpFilter ipFilter;

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(ipFilter.getOrder()).isEqualTo(-150);
    }

    @Test
    void shouldBlockBlacklistedIp() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("192.168.1.100", 8080))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ip:blacklist")).thenReturn(Mono.just("[\"192.168.1.100\"]"));
        when(valueOperations.get("ip:whitelist")).thenReturn(Mono.empty());

        StepVerifier.create(ipFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldAllowWhitelistedIp() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("10.0.0.50", 8080))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ip:blacklist")).thenReturn(Mono.empty());
        when(valueOperations.get("ip:whitelist")).thenReturn(Mono.just("[\"10.0.0.50\"]"));
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(ipFilter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldAllowAllWhenNoRules() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("172.16.0.1", 8080))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ip:blacklist")).thenReturn(Mono.empty());
        when(valueOperations.get("ip:whitelist")).thenReturn(Mono.empty());
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(ipFilter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldExtractIpFromXForwardedFor() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Forwarded-For", "203.0.113.45, 10.0.0.1, 192.168.1.1")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ip:blacklist")).thenReturn(Mono.just("[\"203.0.113.45\"]"));
        when(valueOperations.get("ip:whitelist")).thenReturn(Mono.empty());

        StepVerifier.create(ipFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldExtractIpFromXRealIp() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Real-IP", "198.51.100.23")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ip:blacklist")).thenReturn(Mono.just("[\"198.51.100.23\"]"));
        when(valueOperations.get("ip:whitelist")).thenReturn(Mono.empty());

        StepVerifier.create(ipFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
