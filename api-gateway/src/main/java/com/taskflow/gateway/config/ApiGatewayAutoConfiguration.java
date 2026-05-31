package com.taskflow.gateway.config;

import com.taskflow.gateway.api.AuthenticationService;
import com.taskflow.gateway.api.RateLimitService;
import com.taskflow.gateway.api.TokenService;
import com.taskflow.gateway.internal.auth.AuthenticationServiceImpl;
import com.taskflow.gateway.internal.auth.JwtTokenService;
import com.taskflow.gateway.internal.ratelimit.CaffeineRateLimitService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * API网关模块自动配置
 */
@Configuration
public class ApiGatewayAutoConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public TokenService tokenService() {
        return new JwtTokenService();
    }

    @Bean
    public AuthenticationService authenticationService() {
        return new AuthenticationServiceImpl();
    }

    @Bean
    public RateLimitService rateLimitService() {
        return new CaffeineRateLimitService();
    }
}
