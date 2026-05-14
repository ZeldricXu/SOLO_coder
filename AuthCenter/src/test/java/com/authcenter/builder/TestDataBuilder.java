package com.authcenter.builder;

import com.authcenter.entity.*;

import java.time.LocalDateTime;
import java.util.UUID;

public class TestDataBuilder {
    
    private static final String TEST_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final String TEST_RAW_PASSWORD = "Password@123";
    
    public static User createTestUser() {
        return createTestUser("testuser", "USER");
    }
    
    public static User createTestUser(String username, String role) {
        User user = new User();
        user.setUserId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        user.setUsername(username);
        user.setPasswordHash(TEST_PASSWORD_HASH);
        user.setEmail(username + "@test.com");
        user.setPhone("138" + String.format("%08d", (int)(Math.random() * 100000000)));
        user.setMfaEnabled(false);
        user.setMfaType(null);
        user.setStatus("active");
        user.setCreatedAt(LocalDateTime.now());
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        return user;
    }
    
    public static User createAdminUser() {
        User user = createTestUser("admin", "ADMIN");
        user.setEmail("admin@authcenter.com");
        user.setPhone("13800000001");
        return user;
    }
    
    public static User createLockedUser() {
        User user = createTestUser();
        user.setStatus("locked");
        user.setFailedLoginCount(5);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        return user;
    }
    
    public static User createUserWithFailedLogins(int failedCount) {
        User user = createTestUser();
        user.setFailedLoginCount(failedCount);
        return user;
    }
    
    public static User createUserWithMfa(String mfaType) {
        User user = createTestUser();
        user.setMfaEnabled(true);
        user.setMfaType(mfaType);
        return user;
    }
    
    public static User createDisabledUser() {
        User user = createTestUser();
        user.setStatus("disabled");
        return user;
    }
    
    public static AuthSession createTestSession(String userId, String token) {
        return createTestSession(userId, token, "192.168.1.100", "device_pc_01", "Windows Chrome");
    }
    
    public static AuthSession createTestSession(String userId, String token, String ipAddress, String deviceId, String deviceInfo) {
        AuthSession session = new AuthSession();
        session.setSessionId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        session.setUserId(userId);
        session.setToken(token);
        session.setCreatedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusHours(2));
        session.setIpAddress(ipAddress);
        session.setDeviceId(deviceId);
        session.setDeviceInfo(deviceInfo);
        session.setStatus("active");
        return session;
    }
    
    public static AuthSession createExpiredSession(String userId, String token) {
        AuthSession session = createTestSession(userId, token);
        session.setExpiresAt(LocalDateTime.now().minusHours(1));
        return session;
    }
    
    public static AuthSession createInvalidSession(String userId, String token) {
        AuthSession session = createTestSession(userId, token);
        session.setStatus("expired");
        return session;
    }
    
    public static MfaRecord createMfaRecord(String userId, String mfaType) {
        return createMfaRecord(userId, mfaType, "123456", false);
    }
    
    public static MfaRecord createMfaRecord(String userId, String mfaType, String code, boolean verified) {
        MfaRecord record = new MfaRecord();
        record.setMfaId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        record.setUserId(userId);
        record.setMfaType(mfaType);
        record.setMfaCode(code);
        record.setCreatedAt(LocalDateTime.now());
        record.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        record.setVerified(verified);
        return record;
    }
    
    public static MfaRecord createExpiredMfaRecord(String userId, String mfaType) {
        MfaRecord record = createMfaRecord(userId, mfaType);
        record.setExpiresAt(LocalDateTime.now().minusMinutes(10));
        return record;
    }
    
    public static SecurityPolicy createDefaultSecurityPolicy() {
        return createSecurityPolicy("login_limit", 5, 300000L, 8, true, 7200000L);
    }
    
    public static SecurityPolicy createSecurityPolicy(String policyType, 
                                                      int maxFailedLogin, 
                                                      long lockDuration,
                                                      int passwordMinLength,
                                                      boolean passwordRequireComplex,
                                                      long sessionMaxDuration) {
        SecurityPolicy policy = new SecurityPolicy();
        policy.setPolicyId("policy_" + policyType + "_" + UUID.randomUUID().toString().substring(0, 8));
        policy.setPolicyType(policyType);
        policy.setMaxFailedLogin(maxFailedLogin);
        policy.setLockDuration(lockDuration);
        policy.setPasswordMinLength(passwordMinLength);
        policy.setPasswordRequireComplex(passwordRequireComplex);
        policy.setSessionMaxDuration(sessionMaxDuration);
        policy.setEnabled(true);
        return policy;
    }
    
    public static SecurityPolicy createStrictSecurityPolicy() {
        return createSecurityPolicy("strict_policy", 3, 600000L, 12, true, 3600000L);
    }
    
    public static SecurityPolicy createLenientSecurityPolicy() {
        return createSecurityPolicy("lenient_policy", 10, 60000L, 6, false, 86400000L);
    }
    
    public static UserRole createUserRole(String userId, String role) {
        UserRole userRole = new UserRole();
        userRole.setRoleId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        userRole.setUserId(userId);
        userRole.setRole(role);
        userRole.setCreatedAt(LocalDateTime.now());
        return userRole;
    }
    
    public static OAuthBinding createOAuthBinding(String userId, String provider, String providerUserId) {
        OAuthBinding binding = new OAuthBinding();
        binding.setOauthId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        binding.setUserId(userId);
        binding.setProvider(provider);
        binding.setProviderUserId(providerUserId);
        binding.setBindTime(LocalDateTime.now());
        binding.setStatus("active");
        return binding;
    }
    
    public static AuditLog createAuditLog(String userId, String auditType, String auditResult) {
        AuditLog log = new AuditLog();
        log.setAuditId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        log.setUserId(userId);
        log.setAuditType(auditType);
        log.setAuditResult(auditResult);
        log.setIpAddress("192.168.1.100");
        log.setDeviceInfo("Windows Chrome");
        log.setAuditTime(LocalDateTime.now());
        log.setDetails("Test audit log");
        return log;
    }
    
    public static String getTestRawPassword() {
        return TEST_RAW_PASSWORD;
    }
    
    public static String getTestPasswordHash() {
        return TEST_PASSWORD_HASH;
    }
    
    public static String generateTestToken() {
        return "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." + 
               "eyJ1c2VyX2lkIjoidGVzdF91c2VyX2lkIiwidXNlcm5hbWUiOiJ0ZXN0dXNlciJ9." +
               "test_signature_" + UUID.randomUUID().toString().substring(0, 16);
    }
}