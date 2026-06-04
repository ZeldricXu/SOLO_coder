package com.cicd.server.controller;

import com.cicd.server.dto.LoginRequest;
import com.cicd.server.dto.LoginResponse;
import com.cicd.server.security.JwtUtil;
import com.cicd.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        Authentication authentication = authManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        UserDetails userDetails = userService.loadUserByUsername(request.getUsername());
        String token = jwtUtil.generateToken(userDetails);
        String refreshToken = jwtUtil.generateRefreshToken(userDetails);

        return ResponseEntity.ok(LoginResponse.builder()
            .accessToken(token)
            .refreshToken(refreshToken)
            .tokenType("Bearer")
            .expiresIn(86400L)
            .username(userDetails.getUsername())
            .build());
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refreshToken(@RequestHeader("Authorization") String authHeader) {
        String refreshToken = authHeader.substring(7);
        String username = jwtUtil.extractUsername(refreshToken);
        UserDetails userDetails = userService.loadUserByUsername(username);

        if (jwtUtil.isTokenValid(refreshToken, userDetails)) {
            String newToken = jwtUtil.generateToken(userDetails);
            String newRefreshToken = jwtUtil.generateRefreshToken(userDetails);

            return ResponseEntity.ok(LoginResponse.builder()
                .accessToken(newToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(86400L)
                .username(userDetails.getUsername())
                .build());
        }

        return ResponseEntity.badRequest().build();
    }
}
