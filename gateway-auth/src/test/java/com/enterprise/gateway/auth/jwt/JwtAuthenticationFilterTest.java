package com.enterprise.gateway.auth.jwt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private GatewayFilterChain chain;

    @InjectMocks
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(jwtAuthenticationFilter.getOrder()).isEqualTo(-100);
    }

    @Test
    void shouldAuthenticateValidBearerToken() {
        String validToken = "valid.jwt.token";
        List<String> roles = Arrays.asList("USER", "ADMIN");
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("Authorization", "Bearer " + validToken)
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(jwtTokenProvider.validateToken(validToken)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(validToken)).thenReturn("user123");
        when(jwtTokenProvider.getRolesFromToken(validToken)).thenReturn(roles);
        when(jwtTokenProvider.getClaimsFromToken(validToken)).thenReturn(
                io.jsonwebtoken.Jwts.claims()
                        .add("username", "testuser")
                        .build()
        );
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(jwtAuthenticationFilter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldRejectExpiredToken() {
        String expiredToken = "expired.jwt.token";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("Authorization", "Bearer " + expiredToken)
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(jwtTokenProvider.validateToken(expiredToken)).thenReturn(false);

        StepVerifier.create(jwtAuthenticationFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldRejectInvalidToken() {
        String invalidToken = "invalid.jwt.token";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("Authorization", "Bearer " + invalidToken)
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(jwtTokenProvider.validateToken(invalidToken)).thenReturn(false);

        StepVerifier.create(jwtAuthenticationFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldRejectMissingAuthorizationHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(jwtAuthenticationFilter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    void shouldRejectNonBearerToken() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("Authorization", "Basic somecredentials")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(jwtAuthenticationFilter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
    }
}
