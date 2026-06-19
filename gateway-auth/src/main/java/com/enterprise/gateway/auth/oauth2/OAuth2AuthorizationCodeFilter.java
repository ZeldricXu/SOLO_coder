package com.enterprise.gateway.auth.oauth2;

import com.enterprise.gateway.auth.jwt.JwtTokenProvider;
import com.enterprise.gateway.common.model.UnifiedResponse;
import com.enterprise.gateway.common.util.JacksonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthorizationCodeFilter implements GlobalFilter, Ordered {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${oauth2.success.redirect.url:/}")
    private String successRedirectUrl;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (!matchesCallbackPath(path)) {
            return chain.filter(exchange);
        }

        String code = request.getQueryParams().getFirst("code");

        if (!StringUtils.hasText(code)) {
            return sendError(exchange, "Authorization code not found");
        }

        return exchange.getPrincipal()
                .ofType(OAuth2AuthenticationToken.class)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("OAuth2 authentication not found")))
                .flatMap(authToken -> {
                    OAuth2User user = authToken.getPrincipal();
                    String userId = extractUserId(user);
                    String username = extractUsername(user);
                    List<String> roles = extractRoles(user);

                    String jwt = jwtTokenProvider.generateToken(userId, username, roles);

                    URI redirectUri = URI.create(successRedirectUrl + "?token=" + jwt);
                    exchange.getResponse().setStatusCode(HttpStatus.FOUND);
                    exchange.getResponse().getHeaders().setLocation(redirectUri);
                    return exchange.getResponse().setComplete();
                })
                .onErrorResume(e -> {
                    log.error("OAuth2 authorization code flow failed", e);
                    return sendError(exchange, e.getMessage());
                });
    }

    @Override
    public int getOrder() {
        return -90;
    }

    private boolean matchesCallbackPath(String path) {
        return path.startsWith("/login/oauth2/code/");
    }

    private String extractUserId(OAuth2User user) {
        if (user instanceof OidcUser oidcUser) {
            return oidcUser.getSubject();
        }
        Map<String, Object> attributes = user.getAttributes();
        Object id = attributes.get("sub");
        if (id != null) {
            return id.toString();
        }
        id = attributes.get("id");
        if (id != null) {
            return id.toString();
        }
        return user.getName();
    }

    private String extractUsername(OAuth2User user) {
        if (user instanceof OidcUser oidcUser) {
            String email = oidcUser.getEmail();
            if (email != null) {
                return email;
            }
            return oidcUser.getPreferredUsername();
        }
        Map<String, Object> attributes = user.getAttributes();
        Object email = attributes.get("email");
        if (email != null) {
            return email.toString();
        }
        return user.getName();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(OAuth2User user) {
        Map<String, Object> attributes = user.getAttributes();
        Object roles = attributes.get("roles");
        if (roles instanceof List) {
            return (List<String>) roles;
        }
        return Collections.singletonList("USER");
    }

    private Mono<Void> sendError(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        UnifiedResponse<?> errorResponse = UnifiedResponse.error(401, message);
        String json = JacksonUtil.toJson(errorResponse);
        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }
}
