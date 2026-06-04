package com.cicd.server.security;

import com.cicd.common.enums.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        jwtUtil.setSecret("test-secret-key-for-jwt-token-please-change-in-production-very-long-key-for-hs256");
        jwtUtil.setExpiration(86400000L);
    }

    @Test
    void testGenerateToken() {
        String token = jwtUtil.generateToken("testuser", Set.of(RoleType.PLATFORM_ADMIN));

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertTrue(token.split("\\.").length == 3);
    }

    @Test
    void testExtractUsername() {
        String token = jwtUtil.generateToken("testuser", Set.of(RoleType.PLATFORM_ADMIN));

        String username = jwtUtil.extractUsername(token);

        assertEquals("testuser", username);
    }

    @Test
    void testExtractRoles() {
        Set<RoleType> roles = Set.of(RoleType.PLATFORM_ADMIN, RoleType.DEVELOPER);
        String token = jwtUtil.generateToken("testuser", roles);

        Set<RoleType> extractedRoles = jwtUtil.extractRoles(token);

        assertNotNull(extractedRoles);
        assertEquals(2, extractedRoles.size());
        assertTrue(extractedRoles.contains(RoleType.PLATFORM_ADMIN));
        assertTrue(extractedRoles.contains(RoleType.DEVELOPER));
    }

    @Test
    void testValidateToken() {
        String token = jwtUtil.generateToken("testuser", Set.of(RoleType.PLATFORM_ADMIN));

        assertTrue(jwtUtil.validateToken(token, "testuser"));
        assertFalse(jwtUtil.validateToken(token, "wronguser"));
    }

    @Test
    void testValidateTokenExpired() {
        jwtUtil.setExpiration(1L);
        String token = jwtUtil.generateToken("testuser", Set.of(RoleType.PLATFORM_ADMIN));

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertFalse(jwtUtil.validateToken(token, "testuser"));
    }

    @Test
    void testValidateTokenInvalid() {
        assertFalse(jwtUtil.validateToken("invalid.token.here", "testuser"));
    }

    @Test
    void testValidateTokenNull() {
        assertFalse(jwtUtil.validateToken(null, "testuser"));
    }

    @Test
    void testValidateTokenEmpty() {
        assertFalse(jwtUtil.validateToken("", "testuser"));
    }

    @Test
    void testExtractExpiration() {
        long expiration = 86400000L;
        jwtUtil.setExpiration(expiration);

        String token = jwtUtil.generateToken("testuser", Set.of(RoleType.PLATFORM_ADMIN));
        long extractedExpiration = jwtUtil.extractExpiration(token).getTime();

        assertTrue(extractedExpiration > System.currentTimeMillis());
        assertTrue(extractedExpiration <= System.currentTimeMillis() + expiration);
    }

    @Test
    void testTokenWithMultipleRoles() {
        Set<RoleType> roles = Set.of(
            RoleType.PLATFORM_ADMIN,
            RoleType.PROJECT_OWNER,
            RoleType.DEVELOPER,
            RoleType.VIEWER
        );

        String token = jwtUtil.generateToken("admin", roles);
        Set<RoleType> extractedRoles = jwtUtil.extractRoles(token);

        assertEquals(4, extractedRoles.size());
        assertTrue(extractedRoles.containsAll(roles));
    }
}
