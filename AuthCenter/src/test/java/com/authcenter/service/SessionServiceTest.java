package com.authcenter.service;

import com.authcenter.builder.TestDataBuilder;
import com.authcenter.entity.AuthSession;
import com.authcenter.entity.User;
import com.authcenter.repository.AuthSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("会话服务单元测试 - 设备绑定与IP绑定")
class SessionServiceTest {
    
    @Mock
    private AuthSessionRepository sessionRepository;
    
    @InjectMocks
    private SessionService sessionService;
    
    private User testUser;
    private AuthSession validSession;
    private AuthSession expiredSession;
    private AuthSession invalidSession;
    
    @BeforeEach
    void setUp() {
        testUser = TestDataBuilder.createTestUser();
        validSession = TestDataBuilder.createTestSession(
                testUser.getUserId(), 
                "valid_token_123",
                "192.168.1.100",
                "device_pc_01",
                "Windows Chrome 120"
        );
        expiredSession = TestDataBuilder.createExpiredSession(
                testUser.getUserId(), 
                "expired_token"
        );
        invalidSession = TestDataBuilder.createInvalidSession(
                testUser.getUserId(),
                "invalid_token"
        );
        
        ReflectionTestUtils.setField(sessionService, "sessionMaxDuration", 7200000L);
    }
    
    @Test
    @DisplayName("测试创建会话时绑定设备信息与IP地址")
    void testSessionCreationWithDeviceAndIpBinding() {
        String testToken = "test_token_" + System.currentTimeMillis();
        String testIp = "10.0.0.50";
        String testDeviceId = "device_mobile_02";
        String testDeviceInfo = "iPhone Safari";
        
        when(sessionRepository.save(any(AuthSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        AuthSession createdSession = sessionService.createSession(
                testUser.getUserId(),
                testToken,
                testIp,
                testDeviceId,
                testDeviceInfo
        );
        
        assertNotNull(createdSession);
        assertNotNull(createdSession.getSessionId());
        assertEquals(testToken, createdSession.getToken());
        assertEquals(testUser.getUserId(), createdSession.getUserId());
        assertEquals(testIp, createdSession.getIpAddress());
        assertEquals(testDeviceId, createdSession.getDeviceId());
        assertEquals(testDeviceInfo, createdSession.getDeviceInfo());
        assertEquals("active", createdSession.getStatus());
        assertNotNull(createdSession.getCreatedAt());
        assertNotNull(createdSession.getExpiresAt());
        assertTrue(createdSession.getExpiresAt().isAfter(createdSession.getCreatedAt()));
        
        verify(sessionRepository, times(1)).save(any(AuthSession.class));
    }
    
    @Test
    @DisplayName("测试会话校验时检查绑定信息一致性 - IP一致")
    void testSessionValidationWithConsistentIp() {
        when(sessionRepository.findByToken("valid_token_123")).thenReturn(Optional.of(validSession));
        
        AuthSession retrievedSession = sessionService.getSessionByToken("valid_token_123");
        
        assertNotNull(retrievedSession);
        assertEquals("192.168.1.100", retrievedSession.getIpAddress());
        assertEquals("device_pc_01", retrievedSession.getDeviceId());
    }
    
    @Test
    @DisplayName("测试IP不一致拒绝访问 - 模拟验证场景")
    void testAccessRejectedWithDifferentIp() {
        String originalIp = "192.168.1.100";
        String newIp = "10.0.0.1";
        
        when(sessionRepository.findByToken("valid_token_123")).thenReturn(Optional.of(validSession));
        
        AuthSession session = sessionService.getSessionByToken("valid_token_123");
        
        assertNotNull(session);
        assertNotEquals(newIp, session.getIpAddress());
        assertEquals(originalIp, session.getIpAddress());
        
        boolean ipMatches = session.getIpAddress().equals(newIp);
        assertFalse(ipMatches, "IP地址不匹配时应该拒绝访问");
    }
    
    @Test
    @DisplayName("测试设备信息不一致拒绝访问")
    void testAccessRejectedWithDifferentDeviceInfo() {
        String originalDeviceInfo = "Windows Chrome 120";
        String newDeviceInfo = "Linux Firefox";
        
        when(sessionRepository.findByToken("valid_token_123")).thenReturn(Optional.of(validSession));
        
        AuthSession session = sessionService.getSessionByToken("valid_token_123");
        
        assertNotNull(session);
        assertNotEquals(newDeviceInfo, session.getDeviceInfo());
        assertEquals(originalDeviceInfo, session.getDeviceInfo());
        
        boolean deviceInfoMatches = session.getDeviceInfo().equals(newDeviceInfo);
        assertFalse(deviceInfoMatches, "设备信息不匹配时应该拒绝访问");
    }
    
    @Test
    @DisplayName("测试设备ID不一致拒绝访问")
    void testAccessRejectedWithDifferentDeviceId() {
        String originalDeviceId = "device_pc_01";
        String newDeviceId = "device_laptop_03";
        
        when(sessionRepository.findByToken("valid_token_123")).thenReturn(Optional.of(validSession));
        
        AuthSession session = sessionService.getSessionByToken("valid_token_123");
        
        assertNotNull(session);
        assertNotEquals(newDeviceId, session.getDeviceId());
        assertEquals(originalDeviceId, session.getDeviceId());
        
        boolean deviceIdMatches = session.getDeviceId().equals(newDeviceId);
        assertFalse(deviceIdMatches, "设备ID不匹配时应该拒绝访问");
    }
    
    @Test
    @DisplayName("测试绑定信息完整检查机制")
    void testBindingInformationCompletenessCheck() {
        AuthSession incompleteSession = TestDataBuilder.createTestSession(
                testUser.getUserId(),
                "incomplete_token"
        );
        incompleteSession.setIpAddress(null);
        incompleteSession.setDeviceId(null);
        
        AuthSession completeSession = TestDataBuilder.createTestSession(
                testUser.getUserId(),
                "complete_token",
                "192.168.1.100",
                "device_01",
                "Test Browser"
        );
        
        assertNotNull(incompleteSession.getToken());
        assertNull(incompleteSession.getIpAddress());
        assertNull(incompleteSession.getDeviceId());
        
        boolean isIncompleteBindingComplete = 
                incompleteSession.getIpAddress() != null && 
                incompleteSession.getDeviceId() != null;
        assertFalse(isIncompleteBindingComplete, "不完整的会话绑定信息");
        
        assertNotNull(completeSession.getIpAddress());
        assertNotNull(completeSession.getDeviceId());
        assertNotNull(completeSession.getDeviceInfo());
        
        boolean isCompleteBindingComplete = 
                completeSession.getIpAddress() != null && 
                completeSession.getDeviceId() != null &&
                completeSession.getDeviceInfo() != null;
        assertTrue(isCompleteBindingComplete, "完整的会话绑定信息");
    }
    
    @Test
    @DisplayName("测试有效会话验证成功")
    void testValidSessionVerification() {
        when(sessionRepository.findByToken("valid_token_123")).thenReturn(Optional.of(validSession));
        
        boolean isValid = sessionService.isSessionValid("valid_token_123");
        
        assertTrue(isValid);
        verify(sessionRepository, times(1)).findByToken("valid_token_123");
    }
    
    @Test
    @DisplayName("测试已过期会话验证失败")
    void testExpiredSessionVerification() {
        when(sessionRepository.findByToken("expired_token")).thenReturn(Optional.of(expiredSession));
        
        boolean isValid = sessionService.isSessionValid("expired_token");
        
        assertFalse(isValid);
    }
    
    @Test
    @DisplayName("测试已失效会话验证失败")
    void testInvalidSessionVerification() {
        when(sessionRepository.findByToken("invalid_token")).thenReturn(Optional.of(invalidSession));
        
        boolean isValid = sessionService.isSessionValid("invalid_token");
        
        assertFalse(isValid);
    }
    
    @Test
    @DisplayName("测试不存在的会话验证失败")
    void testNonExistentSessionVerification() {
        when(sessionRepository.findByToken("nonexistent_token")).thenReturn(Optional.empty());
        
        boolean isValid = sessionService.isSessionValid("nonexistent_token");
        
        assertFalse(isValid);
    }
    
    @Test
    @DisplayName("测试会话失效操作")
    void testSessionInvalidation() {
        when(sessionRepository.findByToken("valid_token_123")).thenReturn(Optional.of(validSession));
        when(sessionRepository.save(any(AuthSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        sessionService.invalidateSession("valid_token_123");
        
        verify(sessionRepository, times(1)).findByToken("valid_token_123");
        verify(sessionRepository, times(1)).save(any(AuthSession.class));
        assertEquals("expired", validSession.getStatus());
    }
    
    @Test
    @DisplayName("测试用户所有会话失效")
    void testAllUserSessionsInvalidation() {
        when(sessionRepository.findByUserIdAndStatus(testUser.getUserId(), "active"))
                .thenReturn(java.util.Arrays.asList(validSession));
        when(sessionRepository.save(any(AuthSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        sessionService.invalidateUserSessions(testUser.getUserId());
        
        verify(sessionRepository, times(1)).findByUserIdAndStatus(testUser.getUserId(), "active");
        verify(sessionRepository, times(1)).save(any(AuthSession.class));
    }
    
    @Test
    @DisplayName("测试会话刷新更新过期时间")
    void testSessionRefreshUpdatesExpiration() {
        when(sessionRepository.findBySessionId(validSession.getSessionId())).thenReturn(Optional.of(validSession));
        when(sessionRepository.save(any(AuthSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        AuthSession refreshedSession = sessionService.refreshSession(validSession.getSessionId());
        
        assertNotNull(refreshedSession);
        assertTrue(refreshedSession.getExpiresAt().isAfter(validSession.getCreatedAt()));
        verify(sessionRepository, times(1)).save(any(AuthSession.class));
    }
    
    @Test
    @DisplayName("测试获取会话ID通过令牌")
    void testGetSessionIdByToken() {
        when(sessionRepository.findByToken("valid_token_123")).thenReturn(Optional.of(validSession));
        
        String sessionId = sessionService.getSessionIdByToken("valid_token_123");
        
        assertNotNull(sessionId);
        assertEquals(validSession.getSessionId(), sessionId);
    }
    
    @Test
    @DisplayName("测试获取会话ID通过不存在的令牌")
    void testGetSessionIdByNonExistentToken() {
        when(sessionRepository.findByToken("nonexistent_token")).thenReturn(Optional.empty());
        
        String sessionId = sessionService.getSessionIdByToken("nonexistent_token");
        
        assertNull(sessionId);
    }
    
    @Test
    @DisplayName("测试通过会话ID获取会话")
    void testGetSessionById() {
        when(sessionRepository.findBySessionId(validSession.getSessionId())).thenReturn(Optional.of(validSession));
        
        AuthSession session = sessionService.getSessionById(validSession.getSessionId());
        
        assertNotNull(session);
        assertEquals(validSession.getSessionId(), session.getSessionId());
        assertEquals(validSession.getIpAddress(), session.getIpAddress());
        assertEquals(validSession.getDeviceId(), session.getDeviceId());
    }
    
    @Test
    @DisplayName("测试通过不存在的会话ID获取会话")
    void testGetSessionByNonExistentId() {
        when(sessionRepository.findBySessionId("nonexistent_session_id")).thenReturn(Optional.empty());
        
        AuthSession session = sessionService.getSessionById("nonexistent_session_id");
        
        assertNull(session);
    }
}