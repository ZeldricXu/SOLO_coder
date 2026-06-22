package com.enterprise.gateway.common;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

public class JwtTokenBuilder {

    private String userId = "user-001";
    private String username = "testuser";
    private List<String> roles = List.of("ROLE_USER");
    private String secret = "enterprise-gateway-super-secret-key-2024-abcdefghijklmnopqrstuvwxyz";
    private long expiration = 86400000L;

    private JwtTokenBuilder() {
    }

    public static JwtTokenBuilder builder() {
        return new JwtTokenBuilder();
    }

    public JwtTokenBuilder withUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public JwtTokenBuilder withUsername(String username) {
        this.username = username;
        return this;
    }

    public JwtTokenBuilder withRoles(List<String> roles) {
        this.roles = roles;
        return this;
    }

    public JwtTokenBuilder withSecret(String secret) {
        this.secret = secret;
        return this;
    }

    public JwtTokenBuilder withExpiration(long expiration) {
        this.expiration = expiration;
        return this;
    }

    public String buildToken() {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("user_id", userId)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(secret))
                .compact();
    }

    public String buildExpiredToken() {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() - 1);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("user_id", userId)
                .claim("roles", roles)
                .issuedAt(new Date(now.getTime() - 86400000L))
                .expiration(expiryDate)
                .signWith(getSigningKey(secret))
                .compact();
    }

    public String buildTamperedToken() {
        String wrongSecret = "tampered-wrong-secret-key-that-is-definitely-not-correct-abcdef";

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("user_id", userId)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(wrongSecret))
                .compact();
    }

    private SecretKey getSigningKey(String key) {
        return Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8));
    }
}
