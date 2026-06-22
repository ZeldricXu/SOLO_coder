package com.enterprise.gateway.core.integration.base;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

public class TestJwtHelper {

    private static final String SECRET = "enterprise-gateway-super-secret-key-2024-abcdefghijklmnopqrstuvwxyz";
    private static final long EXPIRATION = 86400000L;
    private static final String USER_ID = "test-user-001";
    private static final String USERNAME = "testuser";

    private TestJwtHelper() {
    }

    public static String generateValidToken() {
        return generateToken(USER_ID, USERNAME, List.of("ROLE_USER"), EXPIRATION);
    }

    public static String generateExpiredToken() {
        Date now = new Date();
        return Jwts.builder()
                .subject(USER_ID)
                .claim("username", USERNAME)
                .claim("user_id", USER_ID)
                .claim("roles", List.of("ROLE_USER"))
                .issuedAt(new Date(now.getTime() - EXPIRATION))
                .expiration(new Date(now.getTime() - 1))
                .signWith(getSigningKey())
                .compact();
    }

    public static String generateAdminToken() {
        return generateToken("admin-001", "admin", List.of("ROLE_ADMIN"), EXPIRATION);
    }

    public static String generateUserToken() {
        return generateToken(USER_ID, USERNAME, List.of("ROLE_USER"), EXPIRATION);
    }

    public static String generateToken(String userId, String username, List<String> roles, long expirationMs) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("user_id", userId)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    private static SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }
}
