package com.authcenter.service;

import com.authcenter.builder.TestDataBuilder;
import com.authcenter.dto.LoginRequest;
import com.authcenter.dto.LoginResponse;
import com.authcenter.entity.AuthSession;
import com.authcenter.entity.User;
import com.authcenter.exception.AuthException;
import com.authcenter.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("认证服务单元测试 - 登录失败限制与账号锁定")
class AuthServiceTest {
    
    @Mock
    private UserService userService;
    
    @Mock
    private TokenService tokenService;
    
    @Mock
    private SessionService sessionService;
    
    @Mock
    private MfaService mfaService;
    
    @Mock
    private AuditService auditService;
    
    @Mock
    private SecurityPolicyService securityPolicyService;
    
    @Mock
    private UserRepository userRepository;
    
    @InjectMocks
    private AuthService authService;
    
    private User testUser;
    private HttpServletRequest mockRequest;
    
    @BeforeEach
    void setUp() {
        testUser = TestDataBuilder.createTestUser("testuser", "USER");
        testUser.setPasswordHash(TestDataBuilder.getTestPasswordHash());
        
        mockRequest = new MockHttpServletRequest();
        ((MockHttpServletRequest) mockRequest).setRemoteAddr("192.168.1.100");
        ((MockHttpServletRequest) mockRequest).addHeader("User-Agent", "Windows Chrome");
        ((MockHttpServletRequest) mockRequest).addHeader("Device-Id", "device_pc_01");
    }
    
    @Test
    @DisplayName("测试连续失败登录计数正确性")
    void testFailedLoginCountIncrement() {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("wrongpassword");
        
        when(userService.getUserByUsername("testuser")).thenReturn(testUser);
        when(userService.verifyPassword("wrongpassword", TestDataBuilder.getTestPasswordHash())).thenReturn(false);
        doNothing().when(userService).updateFailedLoginCount(testUser);
        when(securityPolicyService.shouldLockAccount(anyInt())).thenReturn(false);
        
        assertThrows(AuthException.class, () -> authService.login(request, mockRequest));
        
        verify(userService, times(1)).updateFailedLoginCount(testUser);
        verify(auditService, times(1)).log(eq(testUser.getUserId()), eq("login"), eq("failure"), anyString(), anyString(), anyString());
    }
    
    @Test
    @DisplayName("测试失败次数超限后账号锁定")
    void testAccountLockAfterMaxFailedAttempts() {
        User userWithFailedLogins = TestDataBuilder.createUserWithFailedLogins(2);
        userWithFailedLogins.setPasswordHash(TestDataBuilder.getTestPasswordHash());
        
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("wrongpassword");
        
        when(userService.getUserByUsername("testuser")).thenReturn(userWithFailedLogins);
        when(userService.verifyPassword("wrongpassword", TestDataBuilder.getTestPasswordHash())).thenReturn(false);
        
        doAnswer(invocation -> {
            User userArg = invocation.getArgument(0);
            userArg.setFailedLoginCount(3);
            return null;
        }).when(userService).updateFailedLoginCount(userWithFailedLogins);
        
        when(securityPolicyService.shouldLockAccount(3)).thenReturn(true);
        when(securityPolicyService.getLockDuration()).thenReturn(60000L);
        
        assertThrows(AuthException.class, () -> authService.login(request, mockRequest));
        
        verify(userService, times(1)).updateFailedLoginCount(userWithFailedLogins);
    }
    
    @Test
    @DisplayName("测试锁定时长内拒绝登录尝试")
    void testLoginRejectedDuringLockPeriod() {
        User lockedUser = TestDataBuilder.createLockedUser();
        lockedUser.setPasswordHash(TestDataBuilder.getTestPasswordHash());
        
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword(TestDataBuilder.getTestRawPassword());
        
        when(userService.getUserByUsername("testuser")).thenReturn(lockedUser);
        when(userService.isAccountLocked(lockedUser)).thenReturn(true);
        
        AuthException exception = assertThrows(AuthException.class, () -> authService.login(request, mockRequest));
        assertTrue(exception.getMessage().contains("锁定"));
        
        verify(auditService, times(1)).log(eq(lockedUser.getUserId()), eq("login"), eq("failure"), anyString(), anyString(), anyString());
    }
    
    @Test
    @DisplayName("测试账号解锁后失败计数清零")
    void testFailedCountResetAfterUnlockAndSuccessfulLogin() {
        User userWithFailedLogins = TestDataBuilder.createUserWithFailedLogins(3);
        userWithFailedLogins.setPasswordHash(TestDataBuilder.getTestPasswordHash());
        
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword(TestDataBuilder.getTestRawPassword());
        
        AuthSession mockSession = TestDataBuilder.createTestSession(userWithFailedLogins.getUserId(), "mock_token");
        
        when(userService.getUserByUsername("testuser")).thenReturn(userWithFailedLogins);
        when(userService.verifyPassword(TestDataBuilder.getTestRawPassword(), TestDataBuilder.getTestPasswordHash())).thenReturn(true);
        when(userService.isAccountLocked(userWithFailedLogins)).thenReturn(false);
        when(tokenService.generateToken(userWithFailedLogins)).thenReturn("mock_token");
        when(sessionService.createSession(eq(userWithFailedLogins.getUserId()), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockSession);
        doNothing().when(userService).resetFailedLoginCount(userWithFailedLogins);
        
        LoginResponse response = authService.login(request, mockRequest);
        
        assertNotNull(response);
        verify(userService, times(1)).resetFailedLoginCount(userWithFailedLogins);
        verify(auditService, times(1)).log(eq(userWithFailedLogins.getUserId()), eq("login"), eq("success"), anyString(), anyString(), anyString());
    }
    
    @Test
    @DisplayName("测试不同角色锁定时长差异 - 管理员角色")
    void testDifferentLockDurationForAdminRole() {
        User adminUser = TestDataBuilder.createAdminUser();
        adminUser.setPasswordHash(TestDataBuilder.getTestPasswordHash());
        
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrongpassword");
        
        when(userService.getUserByUsername("admin")).thenReturn(adminUser);
        when(userService.verifyPassword("wrongpassword", TestDataBuilder.getTestPasswordHash())).thenReturn(false);
        
        assertThrows(AuthException.class, () -> authService.login(request, mockRequest));
        
        verify(userService, times(1)).updateFailedLoginCount(adminUser);
        verify(auditService, times(1)).log(eq(adminUser.getUserId()), eq("login"), eq("failure"), anyString(), anyString(), anyString());
    }
    
    @Test
    @DisplayName("测试禁用账号无法登录")
    void testDisabledAccountLoginRejected() {
        User disabledUser = TestDataBuilder.createDisabledUser();
        disabledUser.setPasswordHash(TestDataBuilder.getTestPasswordHash());
        
        LoginRequest request = new LoginRequest();
        request.setUsername("disableduser");
        request.setPassword(TestDataBuilder.getTestRawPassword());
        
        when(userService.getUserByUsername("disableduser")).thenReturn(disabledUser);
        
        AuthException exception = assertThrows(AuthException.class, () -> authService.login(request, mockRequest));
        assertTrue(exception.getMessage().contains("禁用"));
        
        verify(auditService, times(1)).log(eq(disabledUser.getUserId()), eq("login"), eq("failure"), anyString(), anyString(), anyString());
    }
    
    @Test
    @DisplayName("测试正确密码登录成功")
    void testSuccessfulLoginWithCorrectPassword() {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword(TestDataBuilder.getTestRawPassword());
        
        AuthSession mockSession = TestDataBuilder.createTestSession(testUser.getUserId(), "valid_token");
        
        when(userService.getUserByUsername("testuser")).thenReturn(testUser);
        when(userService.verifyPassword(TestDataBuilder.getTestRawPassword(), TestDataBuilder.getTestPasswordHash())).thenReturn(true);
        when(userService.isAccountLocked(testUser)).thenReturn(false);
        when(tokenService.generateToken(testUser)).thenReturn("valid_token");
        when(sessionService.createSession(eq(testUser.getUserId()), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockSession);
        doNothing().when(userService).resetFailedLoginCount(testUser);
        
        LoginResponse response = authService.login(request, mockRequest);
        
        assertNotNull(response);
        assertEquals("valid_token", response.getToken());
        assertNotNull(response.getSessionId());
        verify(userService, times(1)).resetFailedLoginCount(testUser);
        verify(auditService, times(1)).log(eq(testUser.getUserId()), eq("login"), eq("success"), anyString(), anyString(), anyString());
    }
    
    @Test
    @DisplayName("测试多因素认证启用时需要验证码")
    void testMfaRequiredWhenEnabled() {
        User mfaUser = TestDataBuilder.createUserWithMfa("sms");
        mfaUser.setPasswordHash(TestDataBuilder.getTestPasswordHash());
        
        LoginRequest request = new LoginRequest();
        request.setUsername("mfauser");
        request.setPassword(TestDataBuilder.getTestRawPassword());
        
        when(userService.getUserByUsername("mfauser")).thenReturn(mfaUser);
        when(userService.verifyPassword(TestDataBuilder.getTestRawPassword(), TestDataBuilder.getTestPasswordHash())).thenReturn(true);
        when(userService.isAccountLocked(mfaUser)).thenReturn(false);
        
        AuthException exception = assertThrows(AuthException.class, () -> authService.login(request, mockRequest));
        assertEquals(202, exception.getCode());
        assertTrue(exception.getMessage().contains("多因素认证"));
        
        verify(mfaService, times(1)).generateAndSendCode(mfaUser);
        verify(auditService, times(1)).log(eq(mfaUser.getUserId()), eq("login"), eq("mfa_required"), anyString(), anyString(), anyString());
    }
    
    @Test
    @DisplayName("测试提供正确MFA验证码登录成功")
    void testSuccessfulLoginWithCorrectMfaCode() {
        User mfaUser = TestDataBuilder.createUserWithMfa("sms");
        mfaUser.setPasswordHash(TestDataBuilder.getTestPasswordHash());
        
        LoginRequest request = new LoginRequest();
        request.setUsername("mfauser");
        request.setPassword(TestDataBuilder.getTestRawPassword());
        request.setMfaCode("123456");
        
        AuthSession mockSession = TestDataBuilder.createTestSession(mfaUser.getUserId(), "mfa_token");
        
        when(userService.getUserByUsername("mfauser")).thenReturn(mfaUser);
        when(userService.verifyPassword(TestDataBuilder.getTestRawPassword(), TestDataBuilder.getTestPasswordHash())).thenReturn(true);
        when(userService.isAccountLocked(mfaUser)).thenReturn(false);
        when(mfaService.verifyCode(mfaUser.getUserId(), "sms", "123456")).thenReturn(true);
        when(tokenService.generateToken(mfaUser)).thenReturn("mfa_token");
        when(sessionService.createSession(eq(mfaUser.getUserId()), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockSession);
        doNothing().when(userService).resetFailedLoginCount(mfaUser);
        
        LoginResponse response = authService.login(request, mockRequest);
        
        assertNotNull(response);
        verify(mfaService, times(1)).verifyCode(mfaUser.getUserId(), "sms", "123456");
    }
    
    @Test
    @DisplayName("测试错误MFA验证码导致登录失败")
    void testLoginFailedWithIncorrectMfaCode() {
        User mfaUser = TestDataBuilder.createUserWithMfa("sms");
        mfaUser.setPasswordHash(TestDataBuilder.getTestPasswordHash());
        
        LoginRequest request = new LoginRequest();
        request.setUsername("mfauser");
        request.setPassword(TestDataBuilder.getTestRawPassword());
        request.setMfaCode("999999");
        
        when(userService.getUserByUsername("mfauser")).thenReturn(mfaUser);
        when(userService.verifyPassword(TestDataBuilder.getTestRawPassword(), TestDataBuilder.getTestPasswordHash())).thenReturn(true);
        when(userService.isAccountLocked(mfaUser)).thenReturn(false);
        when(mfaService.verifyCode(mfaUser.getUserId(), "sms", "999999"))
                .thenThrow(new AuthException(400, "验证码错误"));
        
        AuthException exception = assertThrows(AuthException.class, () -> authService.login(request, mockRequest));
        assertTrue(exception.getMessage().contains("多因素认证") || exception.getMessage().contains("验证码"));
    }
    
    @Test
    @DisplayName("测试用户不存在时登录失败")
    void testLoginFailedWithNonExistentUser() {
        LoginRequest request = new LoginRequest();
        request.setUsername("nonexistent");
        request.setPassword("anypassword");
        
        when(userService.getUserByUsername("nonexistent"))
                .thenThrow(new AuthException(404, "用户不存在"));
        
        AuthException exception = assertThrows(AuthException.class, () -> authService.login(request, mockRequest));
        assertTrue(exception.getMessage().contains("不存在"));
    }
}