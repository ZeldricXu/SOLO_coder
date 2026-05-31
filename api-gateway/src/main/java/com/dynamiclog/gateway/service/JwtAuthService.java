package com.dynamiclog.gateway.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class JwtAuthService {

    @Value("${jwt.secret:your-256-bit-secret-key-here-must-be-at-least-32-bytes-long}")
    private String secretKey;

    @Value("${jwt.expiration-hours:24}")
    private long expirationHours;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String userId, String username, Set<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationHours * 3600 * 1000);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("roles", new ArrayList<>(roles))
                .issuedAt(now)
                .expiration(expiryDate)
                .id(UUID.randomUUID().toString())
                .signWith(getSigningKey())
                .compact();
    }

    public Mono<Claims> validateToken(String token) {
        return Mono.fromCallable(() -> {
            try {
                return Jwts.parser()
                        .verifyWith(getSigningKey())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
            } catch (JwtException e) {
                log.warn("JWT validation failed: {}", e.getMessage());
                throw new SecurityException("Invalid or expired token");
            }
        });
    }

    public Mono<Boolean> hasRole(Claims claims, String requiredRole) {
        return Mono.fromCallable(() -> {
            List<String> roles = claims.get("roles", List.class);
            return roles != null && roles.contains(requiredRole);
        });
    }

    public Mono<Boolean> hasAnyRole(Claims claims, Set<String> requiredRoles) {
        return Mono.fromCallable(() -> {
            List<String> roles = claims.get("roles", List.class);
            if (roles == null) return false;
            return roles.stream().anyMatch(requiredRoles::contains);
        });
    }
}
