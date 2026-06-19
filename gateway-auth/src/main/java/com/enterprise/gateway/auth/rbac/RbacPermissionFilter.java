package com.enterprise.gateway.auth.rbac;

import com.enterprise.gateway.auth.jwt.JwtAuthenticationToken;
import com.enterprise.gateway.common.model.UnifiedResponse;
import com.enterprise.gateway.common.util.JacksonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RbacPermissionFilter implements GlobalFilter, Ordered {

    private final RbacPermissionService rbacPermissionService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethod().name();

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(securityContext -> {
                    Authentication authentication = securityContext.getAuthentication();
                    if (authentication == null || !authentication.isAuthenticated()) {
                        return chain.filter(exchange);
                    }

                    if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
                        return chain.filter(exchange);
                    }

                    List<String> roles = jwtAuth.getRoles();

                    return rbacPermissionService.hasPermission(roles, path, method)
                            .flatMap(hasPermission -> {
                                if (hasPermission) {
                                    return chain.filter(exchange);
                                } else {
                                    return sendForbiddenError(exchange);
                                }
                            });
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return -80;
    }

    private Mono<Void> sendForbiddenError(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        UnifiedResponse<?> errorResponse = UnifiedResponse.error(403, "Access denied: insufficient permissions");
        String json = JacksonUtil.toJson(errorResponse);
        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }
}
