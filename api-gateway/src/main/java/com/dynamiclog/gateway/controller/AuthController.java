package com.dynamiclog.gateway.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.gateway.service.JwtAuthService;
import com.dynamiclog.gateway.service.RateLimitingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtAuthService authService;
    private final RateLimitingService rateLimitingService;

    @PostMapping("/login")
    public Mono<ApiResponse<Map<String, String>>> login(
            @RequestParam String username,
            @RequestParam String password) {
        return Mono.fromCallable(() -> {
            Set<String> roles = "admin".equals(username) ?
                    Set.of("ADMIN", "SUPER_ADMIN") :
                    Set.of("USER");

            String token = authService.generateToken("user_" + username, username, roles);
            return ApiResponse.success(Map.of("token", token, "tokenType", "Bearer"));
        });
    }

    @GetMapping("/ratelimit/{key}")
    public Mono<ApiResponse<RateLimitingService.RateLimitStatus>> getRateLimitStatus(@PathVariable String key) {
        return rateLimitingService.getStatus(key)
                .map(ApiResponse::success);
    }

    @PostMapping("/ratelimit/{key}")
    public Mono<ApiResponse<Void>> configureRateLimit(
            @PathVariable String key,
            @RequestParam long capacity,
            @RequestParam long refillTokens,
            @RequestParam long refillSeconds) {
        rateLimitingService.configureRateLimit(key, capacity, refillTokens, Duration.ofSeconds(refillSeconds));
        return Mono.just(ApiResponse.success(null));
    }
}
