package com.authcenter.service;

import com.authcenter.entity.AuditLog;
import com.authcenter.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuditService {
    
    @Autowired
    private AuditLogRepository auditLogRepository;
    
    @Async
    public void log(String userId, String auditType, String auditResult, String ipAddress, String deviceInfo, String details) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        auditLog.setUserId(userId);
        auditLog.setAuditType(auditType);
        auditLog.setAuditResult(auditResult);
        auditLog.setIpAddress(ipAddress);
        auditLog.setDeviceInfo(deviceInfo);
        auditLog.setAuditTime(LocalDateTime.now());
        auditLog.setDetails(details);
        
        auditLogRepository.save(auditLog);
    }
    
    public void logLoginSuccess(String userId, String ipAddress, String deviceInfo) {
        log(userId, "login", "success", ipAddress, deviceInfo, "登录成功");
    }
    
    public void logLoginFailure(String userId, String ipAddress, String deviceInfo, String reason) {
        log(userId, "login", "failure", ipAddress, deviceInfo, reason);
    }
    
    public void logLogout(String userId, String ipAddress, String deviceInfo) {
        log(userId, "logout", "success", ipAddress, deviceInfo, "登出成功");
    }
    
    public void logTokenVerify(String userId, String result, String ipAddress) {
        log(userId, "token_verify", result, ipAddress, null, result.equals("success") ? "令牌验证成功" : "令牌验证失败");
    }
    
    public void logMfaVerify(String userId, String result, String ipAddress) {
        log(userId, "mfa_verify", result, ipAddress, null, result.equals("success") ? "多因素认证成功" : "多因素认证失败");
    }
    
    public void logOAuthLogin(String userId, String provider, String result, String ipAddress, String deviceInfo) {
        log(userId, "oauth_login", result, ipAddress, deviceInfo, "OAuth登录: " + provider);
    }
}