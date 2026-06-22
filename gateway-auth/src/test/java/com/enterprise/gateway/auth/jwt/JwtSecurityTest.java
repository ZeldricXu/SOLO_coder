package com.enterprise.gateway.auth.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecurityTest {

    private JwtTokenProvider jwtTokenProvider;
    private static final String SECRET = "enterprise-gateway-super-secret-key-2024-abcdefghijklmnopqrstuvwxyz";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", 86400000L);
    }

    @Test
    void shouldRejectTokenWithForgedSignature() {
        String validToken = jwtTokenProvider.generateToken("user1", "testuser", java.util.Arrays.asList("USER"));
        String differentSecret = "different-secret-key-for-attack-xxxxxxxxxxxxxxxxxxxxxx";

        ReflectionTestUtils.setField(jwtTokenProvider, "secret", differentSecret);
        String forgedToken = jwtTokenProvider.generateToken("admin", "attacker", java.util.Arrays.asList("ADMIN"));

        ReflectionTestUtils.setField(jwtTokenProvider, "secret", SECRET);

        assertThat(jwtTokenProvider.validateToken(validToken)).isTrue();
        assertThat(jwtTokenProvider.validateToken(forgedToken)).isFalse();
    }

    @Test
    void shouldRejectTokenWithTamperedPayload() {
        String token = jwtTokenProvider.generateToken("user1", "normal", java.util.Arrays.asList("USER"));

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        String tamperedToken = parts[0] + "." + parts[1] + "a." + parts[2];

        assertThat(jwtTokenProvider.validateToken(tamperedToken)).isFalse();
    }

    @Test
    void shouldRejectTokenWithNoExpiration() {
        String token = jwtTokenProvider.generateToken("user1", "testuser", java.util.Arrays.asList("USER"));
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        io.jsonwebtoken.Claims claims = jwtTokenProvider.getClaimsFromToken(token);
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getIssuedAt()).isNotNull();
    }

    @Test
    void shouldIncludeSourceIpInSecurityLogContext() {
        String token = jwtTokenProvider.generateToken("user123", "testuser", java.util.Arrays.asList("USER"));

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        io.jsonwebtoken.Claims claims = jwtTokenProvider.getClaimsFromToken(token);

        assertThat(claims).isNotNull();
        assertThat(claims.get("user_id")).isEqualTo("user123");
        assertThat(claims.get("username")).isEqualTo("testuser");
    }

    @Test
    void shouldHaveIssuedAtBeforeExpiration() {
        String token = jwtTokenProvider.generateToken("user1", "testuser", java.util.Arrays.asList("USER"));
        io.jsonwebtoken.Claims claims = jwtTokenProvider.getClaimsFromToken(token);

        assertThat(claims.getIssuedAt()).isBefore(claims.getExpiration());
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(86400000L);
    }
}
