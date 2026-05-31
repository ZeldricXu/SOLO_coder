package com.taskflow.web.controller;

import com.taskflow.common.model.Result;
import com.taskflow.gateway.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/login")
    public Mono<Result<Map<String, Object>>> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        String tenantId = request.getOrDefault("tenantId", "default");

        if ("admin".equals(username) && "admin123".equals(password)) {
            String token = jwtTokenProvider.generateToken(
                    username,
                    tenantId,
                    List.of("ADMIN", "USER")
            );

            return Mono.just(Result.success(Map.of(
                    "token", token,
                    "tokenType", "Bearer",
                    "expiresIn", 86400,
                    "user", Map.of(
                            "id", username,
                            "name", "管理员",
                            "roles", List.of("ADMIN", "USER"),
                            "tenantId", tenantId
                    )
            )));
        }

        return Mono.just(Result.error(401, "用户名或密码错误"));
    }

    @PostMapping("/register")
    public Mono<Result<Map<String, Object>>> register(@RequestBody Map<String, Object> request) {
        return Mono.just(Result.success(Map.of(
                "message", "注册成功",
                "userId", "user_" + System.currentTimeMillis()
        )));
    }

    @PostMapping("/refresh")
    public Mono<Result<Map<String, Object>>> refreshToken(@RequestHeader("Authorization") String authHeader) {
        return Mono.just(Result.success(Map.of(
                "token", "new_token_" + System.currentTimeMillis(),
                "tokenType", "Bearer",
                "expiresIn", 86400
        )));
    }

    @PostMapping("/logout")
    public Mono<Result<Void>> logout() {
        return Mono.just(Result.success(null));
    }
}
