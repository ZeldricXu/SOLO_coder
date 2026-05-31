package com.taskflow.gateway.internal.auth;

import com.taskflow.common.exception.UnauthorizedException;
import com.taskflow.gateway.api.TokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT Token服务实现
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenService implements TokenService {

    @Value("${jwt.secret:taskflow-secret-key-for-jwt-token-generation-2024}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expiration;

    private Key getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public String generateToken(Map<String, Object> claims) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS512)
                .compact();
    }

    @Override
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("Token has expired");
            return false;
        } catch (Exception e) {
            log.warn("Invalid token: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> getClaims(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return new HashMap<>(claims);
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }

    @Override
    public String getUsername(String token) {
        return (String) getClaims(token).get("username");
    }

    @Override
    public String getTenantId(String token) {
        return (String) getClaims(token).getOrDefault("tenantId", "default");
    }

    @Override
    public Mono<String> refreshToken(String oldToken) {
        return Mono.fromCallable(() -> {
            if (!validateToken(oldToken)) {
                throw new UnauthorizedException("Cannot refresh invalid token");
            }

            Map<String, Object> claims = getClaims(oldToken);
            claims.remove("exp");
            claims.remove("iat");

            return generateToken(claims);
        });
    }
}
