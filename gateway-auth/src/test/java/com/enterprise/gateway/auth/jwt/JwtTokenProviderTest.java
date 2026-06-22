package com.enterprise.gateway.auth.jwt;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", "enterprise-gateway-super-secret-key-2024-abcdefghijklmnopqrstuvwxyz");
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", 86400000L);
    }

    @Test
    void shouldGenerateValidToken() {
        String token = jwtTokenProvider.generateToken("user123", "testuser", Arrays.asList("ADMIN", "USER"));
        assertThat(token).isNotNull().isNotEmpty();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void shouldExtractUserIdFromToken() {
        String token = jwtTokenProvider.generateToken("user123", "testuser", Arrays.asList("USER"));
        String userId = jwtTokenProvider.getUserIdFromToken(token);
        assertThat(userId).isEqualTo("user123");
    }

    @Test
    void shouldExtractUsernameFromToken() {
        String token = jwtTokenProvider.generateToken("user123", "testuser", Arrays.asList("USER"));
        Claims claims = jwtTokenProvider.getClaimsFromToken(token);
        String username = claims.get("username", String.class);
        assertThat(username).isEqualTo("testuser");
    }

    @Test
    void shouldExtractRolesFromToken() {
        List<String> expectedRoles = Arrays.asList("ADMIN", "USER", "MODERATOR");
        String token = jwtTokenProvider.generateToken("user123", "testuser", expectedRoles);
        List<String> roles = jwtTokenProvider.getRolesFromToken(token);
        assertThat(roles).containsExactlyElementsOf(expectedRoles);
    }

    @Test
    void shouldReturnAllClaimsFromToken() {
        String token = jwtTokenProvider.generateToken("user123", "testuser", Arrays.asList("ADMIN"));
        Claims claims = jwtTokenProvider.getClaimsFromToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("user123");
        assertThat(claims.get("user_id", String.class)).isEqualTo("user123");
        assertThat(claims.get("username", String.class)).isEqualTo("testuser");
        assertThat(claims.get("roles", List.class)).containsExactly("ADMIN");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void shouldValidateExpiredTokenAsInvalid() {
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", -1000L);
        String token = jwtTokenProvider.generateToken("user123", "testuser", Arrays.asList("USER"));
        assertThat(jwtTokenProvider.validateToken(token)).isFalse();
    }

    @Test
    void shouldValidateTamperedTokenAsInvalid() {
        String token = jwtTokenProvider.generateToken("user123", "testuser", Arrays.asList("USER"));
        String tamperedToken = token + "tampered";
        assertThat(jwtTokenProvider.validateToken(tamperedToken)).isFalse();
    }

    @Test
    void shouldValidateNullTokenAsInvalid() {
        assertThat(jwtTokenProvider.validateToken(null)).isFalse();
    }

    @Test
    void shouldValidateEmptyTokenAsInvalid() {
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
    }

    @Test
    void shouldGenerateTokenWithCustomExpiration() {
        long customExpiration = 3600000L;
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", customExpiration);
        String token = jwtTokenProvider.generateToken("user123", "testuser", Arrays.asList("USER"));
        Claims claims = jwtTokenProvider.getClaimsFromToken(token);
        long actualExpiration = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(actualExpiration).isLessThanOrEqualTo(customExpiration);
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }
}
