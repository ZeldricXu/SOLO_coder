package com.designsystem.util;

import com.designsystem.common.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JWT工具类测试")
@ExtendWith(MockitoExtension.class)
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-should-be-at-least-256-bits-long-enough");
        ReflectionTestUtils.setField(jwtUtil, "expirationHours", 24L);
    }

    @Test
    @DisplayName("生成Token并提取用户名")
    void shouldGenerateTokenAndExtractUsername() {
        String token = jwtUtil.generateToken("testuser");
        assertNotNull(token);
        assertFalse(token.isEmpty());

        String username = jwtUtil.extractUsername(token);
        assertEquals("testuser", username);
    }

    @Test
    @DisplayName("生成带用户ID的Token")
    void shouldGenerateTokenWithUserId() {
        String token = jwtUtil.generateToken("testuser", 123L);
        assertNotNull(token);

        Long userId = jwtUtil.extractUserId(token);
        assertEquals(123L, userId);
    }

    @Test
    @DisplayName("验证有效Token")
    void shouldValidateValidToken() {
        String token = jwtUtil.generateToken("testuser");
        assertTrue(jwtUtil.isTokenValid(token));
        assertTrue(jwtUtil.validateToken(token, "testuser"));
    }

    @Test
    @DisplayName("验证无效Token")
    void shouldRejectInvalidToken() {
        assertFalse(jwtUtil.isTokenValid("invalid-token"));
        assertFalse(jwtUtil.validateToken("invalid-token", "testuser"));
    }

    @Test
    @DisplayName("验证用户名不匹配")
    void shouldRejectTokenWithWrongUsername() {
        String token = jwtUtil.generateToken("testuser");
        assertFalse(jwtUtil.validateToken(token, "wronguser"));
    }

    @Test
    @DisplayName("提取不存在的用户ID返回null")
    void shouldReturnNullForMissingUserId() {
        String token = jwtUtil.generateToken("testuser");
        assertNull(jwtUtil.extractUserId(token));
    }

    @Test
    @DisplayName("过期Token应被拒绝")
    void shouldRejectExpiredToken() throws InterruptedException {
        JwtUtil shortLivedJwt = new JwtUtil();
        ReflectionTestUtils.setField(shortLivedJwt, "secret", "test-secret-key-should-be-at-least-256-bits-long-enough");
        ReflectionTestUtils.setField(shortLivedJwt, "expirationHours", 0L);

        String token = shortLivedJwt.generateToken("testuser");
        Thread.sleep(10);
        assertFalse(shortLivedJwt.isTokenValid(token));
    }

    @Test
    @DisplayName("空Token应被拒绝")
    void shouldRejectNullToken() {
        assertFalse(jwtUtil.isTokenValid(null));
        assertNull(jwtUtil.extractUsername(null));
    }

    @Test
    @DisplayName("篡改Token应被拒绝")
    void shouldRejectTamperedToken() {
        String token = jwtUtil.generateToken("testuser");
        String tamperedToken = token.substring(0, token.length() - 5) + "xxxxx";
        assertFalse(jwtUtil.isTokenValid(tamperedToken));
    }
}
