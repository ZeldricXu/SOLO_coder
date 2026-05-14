package com.authcenter.service;

import com.authcenter.builder.TestDataBuilder;
import com.authcenter.dto.TokenVerifyResponse;
import com.authcenter.entity.User;
import com.authcenter.exception.AuthException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("令牌服务单元测试 - JWT签名、过期处理、伪造检测")
class TokenServiceTest {
    
    @Mock
    private UserService userService;
    
    @Mock
    private SessionService sessionService;
    
    @InjectMocks
    private TokenService tokenService;
    
    private User testUser;
    private String testSecret;
    private long testExpiration;
    
    @BeforeEach
    void setUp() {
        testUser = TestDataBuilder.createTestUser("tokenuser", "USER");
        testSecret = "test-authcenter-secret-key-for-jwt-token-generation-2026-testing-unit-test";
        testExpiration = 3600000L;
        
        ReflectionTestUtils.setField(tokenService, "jwtSecret", testSecret);
        ReflectionTestUtils.setField(tokenService, "jwtExpiration", testExpiration);
    }
    
    private SecretKey getTestSigningKey() {
        byte[] keyBytes = testSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
    
    @Test
    @DisplayName("测试JWT令牌生成")
    void testJwtTokenGeneration() {
        String token = tokenService.generateToken(testUser);
        
        assertNotNull(token);
        assertTrue(token.startsWith("eyJ"));
        
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getTestSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
        
        assertEquals(testUser.getUserId(), claims.getSubject());
        assertEquals(testUser.getUsername(), claims.get("username"));
        assertEquals(testUser.getEmail(), claims.get("email"));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
    }
    
    @Test
    @DisplayName("测试JWT令牌包含正确的用户信息")
    void testTokenContainsCorrectUserInfo() {
        testUser.setEmail("custom@test.com");
        
        String token = tokenService.generateToken(testUser);
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getTestSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
        
        assertEquals(testUser.getUserId(), claims.getSubject());
        assertEquals(testUser.getUsername(), claims.get("username"));
        assertEquals("custom@test.com", claims.get("email"));
    }
    
    @Test
    @DisplayName("测试JWT令牌签名校验成功")
    void testJwtSignatureVerificationSuccess() {
        String validToken = tokenService.generateToken(testUser);
        
        when(sessionService.isSessionValid(validToken)).thenReturn(true);
        when(sessionService.getSessionIdByToken(validToken)).thenReturn("session_123");
        
        TokenVerifyResponse response = tokenService.verifyToken(validToken);
        
        assertNotNull(response);
        assertTrue(response.isValid());
        assertEquals(testUser.getUserId(), response.getUserId());
        assertEquals(testUser.getUsername(), response.getUsername());
    }
    
    @Test
    @DisplayName("测试使用错误签名的令牌校验失败")
    void testTokenWithInvalidSignatureVerificationFailed() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("user_id", testUser.getUserId());
        claims.put("username", testUser.getUsername());
        
        String wrongSecret = "different-secret-key-for-token-forgery-test-key";
        SecretKey wrongKey = Keys.hmacShaKeyFor(wrongSecret.getBytes(StandardCharsets.UTF_8));
        
        String invalidToken = Jwts.builder()
                .setClaims(claims)
                .setSubject(testUser.getUserId())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + testExpiration))
                .signWith(wrongKey, SignatureAlgorithm.HS256)
                .compact();
        
        assertThrows(AuthException.class, () -> tokenService.verifyToken(invalidToken));
    }
    
    @Test
    @DisplayName("测试令牌过期处理")
    void testExpiredTokenVerification() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("user_id", testUser.getUserId());
        claims.put("username", testUser.getUsername());
        
        String expiredToken = Jwts.builder()
                .setClaims(claims)
                .setSubject(testUser.getUserId())
                .setIssuedAt(new Date(System.currentTimeMillis() - 7200000))
                .setExpiration(new Date(System.currentTimeMillis() - 3600000))
                .signWith(getTestSigningKey(), SignatureAlgorithm.HS256)
                .compact();
        
        TokenVerifyResponse response = tokenService.verifyToken(expiredToken);
        
        assertNotNull(response);
        assertFalse(response.isValid());
        assertNull(response.getUserId());
        assertNull(response.getUsername());
    }
    
    @Test
    @DisplayName("测试令牌伪造检测 - 篡改payload")
    void testTokenForgeryDetectionWithTamperedPayload() {
        String originalToken = tokenService.generateToken(testUser);
        
        String[] parts = originalToken.split("\\.");
        String tamperedToken = parts[0] + "." + "tampered_payload" + "." + parts[2];
        
        assertThrows(AuthException.class, () -> tokenService.verifyToken(tamperedToken));
    }
    
    @Test
    @DisplayName("测试令牌伪造检测 - 篡改签名")
    void testTokenForgeryDetectionWithTamperedSignature() {
        String originalToken = tokenService.generateToken(testUser);
        
        String[] parts = originalToken.split("\\.");
        String tamperedToken = parts[0] + "." + parts[1] + "." + "forged_signature_123456789";
        
        assertThrows(AuthException.class, () -> tokenService.verifyToken(tamperedToken));
    }
    
    @Test
    @DisplayName("测试完全伪造令牌检测")
    void testCompletelyForgedToken() {
        Map<String, Object> fakeClaims = new HashMap<>();
        fakeClaims.put("user_id", "fake_user_id");
        fakeClaims.put("username", "fakeuser");
        fakeClaims.put("role", "ADMIN");
        
        SecretKey fakeKey = Keys.hmacShaKeyFor("fake-secret-key-for-forgery".getBytes(StandardCharsets.UTF_8));
        
        String forgedToken = Jwts.builder()
                .setClaims(fakeClaims)
                .setSubject("fake_user_id")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + testExpiration))
                .signWith(fakeKey, SignatureAlgorithm.HS256)
                .compact();
        
        assertThrows(AuthException.class, () -> tokenService.verifyToken(forgedToken));
    }
    
    @Test
    @DisplayName("测试从令牌提取用户ID")
    void testGetUserIdFromToken() {
        String token = tokenService.generateToken(testUser);
        
        String userId = tokenService.getUserIdFromToken(token);
        
        assertEquals(testUser.getUserId(), userId);
    }
    
    @Test
    @DisplayName("测试令牌有效性检查 - 有效令牌")
    void testTokenValidityCheckValidToken() {
        String validToken = tokenService.generateToken(testUser);
        
        boolean isValid = tokenService.isTokenValid(validToken);
        
        assertTrue(isValid);
    }
    
    @Test
    @DisplayName("测试令牌有效性检查 - 过期令牌")
    void testTokenValidityCheckExpiredToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("user_id", testUser.getUserId());
        
        String expiredToken = Jwts.builder()
                .setClaims(claims)
                .setSubject(testUser.getUserId())
                .setIssuedAt(new Date(System.currentTimeMillis() - 7200000))
                .setExpiration(new Date(System.currentTimeMillis() - 3600000))
                .signWith(getTestSigningKey(), SignatureAlgorithm.HS256)
                .compact();
        
        boolean isValid = tokenService.isTokenValid(expiredToken);
        
        assertFalse(isValid);
    }
    
    @Test
    @DisplayName("测试令牌有效性检查 - 无效格式令牌")
    void testTokenValidityCheckInvalidFormat() {
        boolean isValid = tokenService.isTokenValid("not-a-valid-jwt-token");
        
        assertFalse(isValid);
    }
    
    @Test
    @DisplayName("测试会话失效时令牌验证失败")
    void testTokenVerificationFailsWhenSessionInvalid() {
        String validToken = tokenService.generateToken(testUser);
        
        when(sessionService.isSessionValid(validToken)).thenReturn(false);
        
        TokenVerifyResponse response = tokenService.verifyToken(validToken);
        
        assertNotNull(response);
        assertFalse(response.isValid());
    }
    
    @Test
    @DisplayName("测试令牌过期时会话仍有效")
    void testTokenExpiredButSessionActive() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("user_id", testUser.getUserId());
        claims.put("username", testUser.getUsername());
        
        String expiredToken = Jwts.builder()
                .setClaims(claims)
                .setSubject(testUser.getUserId())
                .setIssuedAt(new Date(System.currentTimeMillis() - 7200000))
                .setExpiration(new Date(System.currentTimeMillis() - 3600000))
                .signWith(getTestSigningKey(), SignatureAlgorithm.HS256)
                .compact();
        
        when(sessionService.isSessionValid(expiredToken)).thenReturn(true);
        
        TokenVerifyResponse response = tokenService.verifyToken(expiredToken);
        
        assertNotNull(response);
        assertFalse(response.isValid());
    }
    
    @Test
    @DisplayName("测试令牌验证包含会话ID")
    void testTokenVerificationIncludesSessionId() {
        String validToken = tokenService.generateToken(testUser);
        String expectedSessionId = "session_test_123456";
        
        when(sessionService.isSessionValid(validToken)).thenReturn(true);
        when(sessionService.getSessionIdByToken(validToken)).thenReturn(expectedSessionId);
        
        TokenVerifyResponse response = tokenService.verifyToken(validToken);
        
        assertEquals(expectedSessionId, response.getSessionId());
    }
    
    @Test
    @DisplayName("测试无效令牌获取用户ID失败")
    void testGetUserIdFromInvalidToken() {
        assertThrows(AuthException.class, () -> tokenService.getUserIdFromToken("invalid_token"));
    }
    
    @Test
    @DisplayName("测试令牌签名算法正确性")
    void testTokenSignatureAlgorithm() {
        String token = tokenService.generateToken(testUser);
        
        io.jsonwebtoken.Jws<Claims> jws = Jwts.parserBuilder()
                .setSigningKey(getTestSigningKey())
                .build()
                .parseClaimsJws(token);
        
        assertEquals(SignatureAlgorithm.HS256.getValue(), jws.getHeader().getAlgorithm());
    }
}