package com.taskflow.gateway.config;

import com.taskflow.gateway.filter.AuthenticationFilter;
import com.taskflow.gateway.filter.CorsFilter;
import com.taskflow.gateway.filter.RateLimitFilter;
import com.taskflow.gateway.filter.TenantContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * 安全配置
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthenticationFilter authenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final TenantContextFilter tenantContextFilter;
    private final CorsFilter corsFilter;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/v1/auth/**", "/actuator/**", "/health").permitAll()
                        .anyExchange().authenticated()
                )
                .addFilterAt(corsFilter, SecurityWebFiltersOrder.CORS)
                .addFilterAt(tenantContextFilter, SecurityWebFiltersOrder.FIRST)
                .addFilterAt(rateLimitFilter, SecurityWebFiltersOrder.SECOND)
                .addFilterAt(authenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION);

        return http.build();
    }
}
