package com.authcenter.controller;

import com.authcenter.dto.*;
import com.authcenter.service.AuthService;
import com.authcenter.service.OAuthService;
import com.authcenter.service.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    
    @Autowired
    private AuthService authService;
    
    @Autowired
    private TokenService tokenService;
    
    @Autowired
    private OAuthService oauthService;
    
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, 
                                           HttpServletRequest httpRequest) {
        LoginResponse response = authService.login(request, httpRequest);
        return ApiResponse.success(response);
    }
    
    @PostMapping("/verify")
    public ApiResponse<TokenVerifyResponse> verifyToken(@Valid @RequestBody TokenVerifyRequest request) {
        TokenVerifyResponse response = tokenService.verifyToken(request.getToken());
        return ApiResponse.success(response);
    }
    
    @PostMapping("/oauth")
    public ApiResponse<LoginResponse> oauthLogin(@Valid @RequestBody OAuthLoginRequest request,
                                                 HttpServletRequest httpRequest) {
        String ipAddress = getClientIp(httpRequest);
        String deviceInfo = httpRequest.getHeader("User-Agent");
        String deviceId = httpRequest.getHeader("Device-Id");
        
        LoginResponse response = oauthService.loginWithOAuth(request, ipAddress, deviceInfo, deviceId);
        return ApiResponse.success(response);
    }
    
    @PostMapping("/logout")
    public ApiResponse<String> logout(@RequestHeader("Authorization") String authHeader,
                                      HttpServletRequest httpRequest) {
        String token = authHeader.replace("Bearer ", "");
        authService.logout(token, httpRequest);
        return ApiResponse.success("登出成功", null);
    }
    
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}