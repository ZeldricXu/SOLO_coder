package com.taskflow.gateway.controller;

import com.taskflow.common.model.Result;
import com.taskflow.gateway.api.AuthenticationService;
import com.taskflow.gateway.api.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 认证控制器
 * 仅依赖接口，不依赖具体实现
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationService authenticationService;
    private final TokenService tokenService;

    @PostMapping("/login")
    public Mono<Result<Map<String, Object>>> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        return authenticationService.authenticate(username, password)
                .map(result -> {
                    if (!result.success()) {
                        return Result.<Map<String, Object>>error(result.message());
                    }
                    return Result.success(Map.of(
                            "token", result.token(),
                            "username", result.username(),
                            "tenantId", result.tenantId(),
                            "tokenType", "Bearer"
                    ));
                });
    }

    @PostMapping("/logout")
    public Mono<Result<Void>> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        return authenticationService.logout(token)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/refresh")
    public Mono<Result<Map<String, Object>>> refreshToken(@RequestBody Map<String, String> request) {
        String oldToken = request.get("token");
        return tokenService.refreshToken(oldToken)
                .map(newToken -> Result.success(Map.of(
                        "token", newToken,
                        "tokenType", "Bearer"
                )));
    }

    @GetMapping("/validate")
    public Mono<Result<Map<String, Object>>> validateToken(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;

        if (!tokenService.validateToken(token)) {
            return Mono.just(Result.error("Invalid or expired token"));
        }

        return Mono.just(Result.success(Map.of(
                "valid", true,
                "username", tokenService.getUsername(token),
                "tenantId", tokenService.getTenantId(token),
                "claims", tokenService.getClaims(token)
        )));
    }
}
