package com.taskflow.gateway.internal.auth;

import com.taskflow.gateway.api.AuthenticationService;
import com.taskflow.gateway.api.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证服务实现
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {

    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    private static final Map<String, String> USER_STORE = new HashMap<>();

    static {
        USER_STORE.put("admin", "admin123");
        USER_STORE.put("user", "user123");
    }

    @Override
    public Mono<AuthenticationResult> authenticate(String username, String password) {
        return Mono.fromCallable(() -> {
            String storedPassword = USER_STORE.get(username);

            if (storedPassword == null || !passwordEncoder.matches(password, passwordEncoder.encode(storedPassword))) {
                log.warn("Authentication failed for user: {}", username);
                return new AuthenticationResult(false, null, username, "default", "Invalid username or password");
            }

            Map<String, Object> claims = new HashMap<>();
            claims.put("username", username);
            claims.put("tenantId", "default");
            claims.put("roles", Collections.singletonList("ROLE_USER"));

            String token = tokenService.generateToken(claims);
            log.info("User {} authenticated successfully", username);

            return new AuthenticationResult(true, token, username, "default", "Authentication successful");
        });
    }

    @Override
    public Mono<Authentication> getAuthentication(String token) {
        return Mono.fromCallable(() -> {
            if (!tokenService.validateToken(token)) {
                return null;
            }

            String username = tokenService.getUsername(token);
            String tenantId = tokenService.getTenantId(token);

            Map<String, Object> claims = tokenService.getClaims(token);
            @SuppressWarnings("unchecked")
            java.util.List<String> roles = (java.util.List<String>) claims.getOrDefault("roles", Collections.emptyList());

            var authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            authentication.setDetails(Map.of("tenantId", tenantId));

            return authentication;
        });
    }

    @Override
    public Mono<Void> logout(String token) {
        return Mono.fromRunnable(() -> {
            log.info("User logged out, token: {}", token.substring(0, Math.min(20, token.length())));
        }).then();
    }
}
