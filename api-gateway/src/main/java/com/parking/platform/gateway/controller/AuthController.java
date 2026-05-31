package com.parking.platform.gateway.controller;

import com.parking.platform.common.context.RequestContext;
import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.gateway.config.JwtConfig;
import com.parking.platform.gateway.dto.LoginRequest;
import com.parking.platform.gateway.dto.LoginResponse;
import com.parking.platform.gateway.service.AuthenticationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationService authenticationService;
    private final JwtConfig jwtConfig;

    public AuthController(AuthenticationService authenticationService, JwtConfig jwtConfig) {
        this.authenticationService = authenticationService;
        this.jwtConfig = jwtConfig;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt for user: {}", request.getUsername());

        String token = authenticationService.login(request.getUsername(), request.getPassword());
        LoginResponse response = new LoginResponse(
                token,
                jwtConfig.getExpiration() / 1000,
                request.getUsername()
        );

        return ApiResponse.success(response);
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> getCurrentUser() {
        RequestContext ctx = RequestContext.current();

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", ctx.getUserId());
        userInfo.put("roles", ctx.getUserRoles());
        userInfo.put("requestId", ctx.getRequestId());

        return ApiResponse.success(userInfo);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        RequestContext ctx = RequestContext.current();
        log.info("User {} logged out", ctx.getUserId());
        return ApiResponse.success(null);
    }

    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("OK");
    }
}
