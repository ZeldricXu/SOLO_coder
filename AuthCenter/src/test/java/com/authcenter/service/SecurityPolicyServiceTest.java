package com.authcenter.service;

import com.authcenter.builder.TestDataBuilder;
import com.authcenter.entity.SecurityPolicy;
import com.authcenter.repository.SecurityPolicyRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("安全策略服务单元测试 - 自定义策略配置与动态加载")
class SecurityPolicyServiceTest {
    
    @Mock
    private SecurityPolicyRepository policyRepository;
    
    @InjectMocks
    private SecurityPolicyService policyService;
    
    private SecurityPolicy strictPolicy;
    private SecurityPolicy lenientPolicy;
    private SecurityPolicy defaultPolicy;
    private SecurityPolicy passwordPolicy;
    private SecurityPolicy loginPolicy;
    
    @BeforeEach
    void setUp() {
        strictPolicy = TestDataBuilder.createStrictSecurityPolicy();
        lenientPolicy = TestDataBuilder.createLenientSecurityPolicy();
        defaultPolicy = TestDataBuilder.createDefaultSecurityPolicy();
        passwordPolicy = TestDataBuilder.createSecurityPolicy(
                "password_policy", 5, 300000L, 8, true, 7200000L);
        loginPolicy = TestDataBuilder.createSecurityPolicy(
                "login_limit", 5, 300000L, 8, true, 7200000L);
        
        ReflectionTestUtils.setField(policyService, "defaultMaxFailedLogin", 5);
        ReflectionTestUtils.setField(policyService, "defaultLockDuration", 300000L);
        ReflectionTestUtils.setField(policyService, "defaultPasswordMinLength", 8);
        ReflectionTestUtils.setField(policyService, "defaultPasswordRequireComplex", true);
    }
    
    @Test
    @DisplayName("测试严格安全策略下的行为 - 最大失败登录次数")
    void testStrictPolicyMaxFailedLogin() {
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(strictPolicy));
        
        int maxFailedLogin = policyService.getMaxFailedLogin();
        
        assertEquals(3, maxFailedLogin);
        verify(policyRepository, times(1)).findByPolicyType("login_limit");
    }
    
    @Test
    @DisplayName("测试宽松安全策略下的行为 - 最大失败登录次数")
    void testLenientPolicyMaxFailedLogin() {
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(lenientPolicy));
        
        int maxFailedLogin = policyService.getMaxFailedLogin();
        
        assertEquals(10, maxFailedLogin);
    }
    
    @Test
    @DisplayName("测试严格策略下账号锁定时长")
    void testStrictPolicyLockDuration() {
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(strictPolicy));
        
        long lockDuration = policyService.getLockDuration();
        
        assertEquals(600000L, lockDuration);
    }
    
    @Test
    @DisplayName("测试宽松策略下账号锁定时长")
    void testLenientPolicyLockDuration() {
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(lenientPolicy));
        
        long lockDuration = policyService.getLockDuration();
        
        assertEquals(60000L, lockDuration);
    }
    
    @Test
    @DisplayName("测试严格策略下密码最小长度")
    void testStrictPolicyPasswordMinLength() {
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(strictPolicy));
        
        int minLength = policyService.getPasswordMinLength();
        
        assertEquals(12, minLength);
    }
    
    @Test
    @DisplayName("测试宽松策略下密码最小长度")
    void testLenientPolicyPasswordMinLength() {
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(lenientPolicy));
        
        int minLength = policyService.getPasswordMinLength();
        
        assertEquals(6, minLength);
    }
    
    @Test
    @DisplayName("测试严格策略下密码复杂度要求")
    void testStrictPolicyPasswordComplexity() {
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(strictPolicy));
        
        boolean requireComplex = policyService.isPasswordRequireComplex();
        
        assertTrue(requireComplex);
    }
    
    @Test
    @DisplayName("测试宽松策略下密码复杂度要求")
    void testLenientPolicyPasswordComplexity() {
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(lenientPolicy));
        
        boolean requireComplex = policyService.isPasswordRequireComplex();
        
        assertFalse(requireComplex);
    }
    
    @Test
    @DisplayName("测试严格策略下判断是否锁定账号")
    void testStrictPolicyShouldLockAccount() {
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(strictPolicy));
        
        assertFalse(policyService.shouldLockAccount(2));
        assertTrue(policyService.shouldLockAccount(3));
        assertTrue(policyService.shouldLockAccount(5));
    }
    
    @Test
    @DisplayName("测试宽松策略下判断是否锁定账号")
    void testLenientPolicyShouldLockAccount() {
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(lenientPolicy));
        
        assertFalse(policyService.shouldLockAccount(5));
        assertFalse(policyService.shouldLockAccount(9));
        assertTrue(policyService.shouldLockAccount(10));
        assertTrue(policyService.shouldLockAccount(15));
    }
    
    @Test
    @DisplayName("测试严格策略下的密码验证")
    void testStrictPolicyPasswordValidation() {
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(strictPolicy));
        
        assertFalse(policyService.validatePassword("short"));
        assertFalse(policyService.validatePassword("alllowercase123"));
        assertFalse(policyService.validatePassword("ALLUPPERCASE123"));
        assertFalse(policyService.validatePassword("NoSpecialChar123"));
        assertTrue(policyService.validatePassword("StrongP@ssw0rd"));
        assertTrue(policyService.validatePassword("MyStr0ng!P@ssword"));
    }
    
    @Test
    @DisplayName("测试宽松策略下的密码验证")
    void testLenientPolicyPasswordValidation() {
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(lenientPolicy));
        
        assertFalse(policyService.validatePassword("123"));
        assertFalse(policyService.validatePassword("ab"));
        assertTrue(policyService.validatePassword("simple1"));
        assertTrue(policyService.validatePassword("password"));
        assertTrue(policyService.validatePassword("123456"));
    }
    
    @Test
    @DisplayName("测试默认策略当数据库无策略时")
    void testDefaultPolicyWhenNoPolicyInDatabase() {
        when(policyRepository.findByPolicyType(anyString())).thenReturn(Optional.empty());
        
        assertEquals(5, policyService.getMaxFailedLogin());
        assertEquals(300000L, policyService.getLockDuration());
        assertEquals(8, policyService.getPasswordMinLength());
        assertTrue(policyService.isPasswordRequireComplex());
    }
    
    @Test
    @DisplayName("测试策略动态加载 - 切换不同策略")
    void testDynamicPolicyLoading() {
        when(policyRepository.findByPolicyType("login_limit"))
                .thenReturn(Optional.of(strictPolicy))
                .thenReturn(Optional.of(lenientPolicy));
        
        int firstResult = policyService.getMaxFailedLogin();
        int secondResult = policyService.getMaxFailedLogin();
        
        assertEquals(3, firstResult);
        assertEquals(10, secondResult);
        verify(policyRepository, times(2)).findByPolicyType("login_limit");
    }
    
    @Test
    @DisplayName("测试不同策略类型的动态加载")
    void testDifferentPolicyTypesLoading() {
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(loginPolicy));
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(passwordPolicy));
        
        SecurityPolicy loginPolicyResult = policyService.getPolicy("login_limit");
        SecurityPolicy passwordPolicyResult = policyService.getPolicy("password_policy");
        
        assertEquals("login_limit", loginPolicyResult.getPolicyType());
        assertEquals("password_policy", passwordPolicyResult.getPolicyType());
    }
    
    @Test
    @DisplayName("测试复杂密码验证 - 包含所有必要字符类型")
    void testComplexPasswordValidation() {
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(strictPolicy));
        
        String complexPassword = "Admin@123";
        String tooShort = "Adm@1";
        String noUpperCase = "admin@123";
        String noLowerCase = "ADMIN@123";
        String noDigit = "Admin@Password";
        String noSpecial = "Admin123456";
        
        assertTrue(policyService.validatePassword(complexPassword));
        assertFalse(policyService.validatePassword(tooShort));
        assertFalse(policyService.validatePassword(noUpperCase));
        assertFalse(policyService.validatePassword(noLowerCase));
        assertFalse(policyService.validatePassword(noDigit));
        assertFalse(policyService.validatePassword(noSpecial));
    }
    
    @Test
    @DisplayName("测试策略禁用时使用默认值")
    void testDisabledPolicyUsesDefault() {
        SecurityPolicy disabledPolicy = TestDataBuilder.createSecurityPolicy(
                "login_limit", 10, 60000L, 6, false, 86400000L);
        disabledPolicy.setEnabled(false);
        
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(disabledPolicy));
        
        SecurityPolicy result = policyService.getPolicy("login_limit");
        
        assertEquals(10, result.getMaxFailedLogin());
        assertEquals(60000L, result.getLockDuration());
    }
    
    @Test
    @DisplayName("测试严格策略与宽松策略的对比")
    void testStrictVsLenientPolicyComparison() {
        when(policyRepository.findByPolicyType("login_limit"))
                .thenReturn(Optional.of(strictPolicy));
        when(policyRepository.findByPolicyType("password_policy"))
                .thenReturn(Optional.of(strictPolicy));
        
        assertTrue(policyService.shouldLockAccount(3));
        assertFalse(policyService.validatePassword("simple123"));
        
        when(policyRepository.findByPolicyType("login_limit"))
                .thenReturn(Optional.of(lenientPolicy));
        when(policyRepository.findByPolicyType("password_policy"))
                .thenReturn(Optional.of(lenientPolicy));
        
        assertFalse(policyService.shouldLockAccount(3));
        assertTrue(policyService.validatePassword("simple123"));
    }
    
    @Test
    @DisplayName("测试空密码验证")
    void testNullPasswordValidation() {
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(defaultPolicy));
        
        assertFalse(policyService.validatePassword(null));
        assertFalse(policyService.validatePassword(""));
    }
    
    @Test
    @DisplayName("测试策略中null字段时使用默认值")
    void testNullPolicyFieldsUseDefault() {
        SecurityPolicy incompletePolicy = new SecurityPolicy();
        incompletePolicy.setPolicyId("incomplete");
        incompletePolicy.setPolicyType("incomplete_policy");
        incompletePolicy.setMaxFailedLogin(null);
        incompletePolicy.setLockDuration(null);
        incompletePolicy.setPasswordMinLength(null);
        incompletePolicy.setPasswordRequireComplex(null);
        incompletePolicy.setEnabled(true);
        
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(incompletePolicy));
        when(policyRepository.findByPolicyType("password_policy")).thenReturn(Optional.of(incompletePolicy));
        
        assertEquals(5, policyService.getMaxFailedLogin());
        assertEquals(300000L, policyService.getLockDuration());
        assertEquals(8, policyService.getPasswordMinLength());
        assertTrue(policyService.isPasswordRequireComplex());
    }
    
    @Test
    @DisplayName("测试连续失败登录计数边界值")
    void testFailedLoginCountBoundaryValues() {
        when(policyRepository.findByPolicyType("login_limit")).thenReturn(Optional.of(defaultPolicy));
        
        assertFalse(policyService.shouldLockAccount(0));
        assertFalse(policyService.shouldLockAccount(4));
        assertTrue(policyService.shouldLockAccount(5));
        assertTrue(policyService.shouldLockAccount(100));
    }
}