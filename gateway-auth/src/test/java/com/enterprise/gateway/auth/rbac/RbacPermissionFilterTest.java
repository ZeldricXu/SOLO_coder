package com.enterprise.gateway.auth.rbac;

import com.enterprise.gateway.auth.jwt.JwtAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
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
class RbacPermissionFilterTest {

    @Mock
    private RbacPermissionService rbacPermissionService;

    @Mock
    private GatewayFilterChain chain;

    @InjectMocks
    private RbacPermissionFilter rbacPermissionFilter;

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(rbacPermissionFilter.getOrder()).isEqualTo(-80);
    }

    @Test
    void shouldAllowRequestWithMatchingPermission() {
        List<String> roles = Arrays.asList("ADMIN", "USER");
        JwtAuthenticationToken authentication = new JwtAuthenticationToken("user123", "testuser", roles);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(rbacPermissionService.hasPermission(roles, "/api/users", "GET")).thenReturn(Mono.just(true));
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(rbacPermissionFilter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldRejectRequestWithoutPermission() {
        List<String> roles = Arrays.asList("USER");
        JwtAuthenticationToken authentication = new JwtAuthenticationToken("user123", "testuser", roles);
        MockServerHttpRequest request = MockServerHttpRequest.delete("/api/admin/users")
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        when(rbacPermissionService.hasPermission(roles, "/api/admin/users", "DELETE")).thenReturn(Mono.just(false));

        StepVerifier.create(rbacPermissionFilter.filter(exchange, chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
