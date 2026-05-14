package com.authcenter.service;

import com.authcenter.dto.LoginRequest;
import com.authcenter.dto.LoginResponse;
import com.authcenter.entity.AuthSession;
import com.authcenter.entity.User;
import com.authcenter.exception.AuthException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;

@Service
public class AuthService {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private TokenService tokenService;
    
    @Autowired
    private SessionService sessionService;
    
    @Autowired
    private MfaService mfaService;
    
    @Autowired
    private AuditService auditService;
    
    @Autowired
    private SecurityPolicyService securityPolicyService;
    
    public LoginResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String ipAddress = getClientIp(httpRequest);
        String deviceInfo = httpRequest.getHeader("User-Agent");
        String deviceId = httpRequest.getHeader("Device-Id");
        
        try {
            User user = userService.getUserByUsername(request.getUsername());
            
            if ("disabled".equals(user.getStatus())) {
                auditService.log(user.getUserId(), "login", "failure", ipAddress, deviceInfo, "用户已禁用");
                throw new AuthException(401, "用户已被禁用");
            }
            
            if (userService.isAccountLocked(user)) {
                auditService.log(user.getUserId(), "login", "failure", ipAddress, deviceInfo, "账号已锁定");
                throw new AuthException(401, "账号已锁定，请稍后再试");
            }
            
            if (!userService.verifyPassword(request.getPassword(), user.getPasswordHash())) {
                userService.updateFailedLoginCount(user);
                auditService.log(user.getUserId(), "login", "failure", ipAddress, deviceInfo, "密码错误");
                throw new AuthException(401, "用户名或密码错误");
            }
            
            if (user.getMfaEnabled()) {
                if (request.getMfaCode() == null || request.getMfaCode().isEmpty()) {
                    mfaService.generateAndSendCode(user);
                    auditService.log(user.getUserId(), "login", "mfa_required", ipAddress, deviceInfo, "需要多因素认证");
                    throw new AuthException(202, "需要多因素认证验证码");
                }
                if (!mfaService.verifyCode(user.getUserId(), user.getMfaType(), request.getMfaCode())) {
                    auditService.log(user.getUserId(), "login", "failure", ipAddress, deviceInfo, "多因素认证失败");
                    throw new AuthException(401, "多因素认证失败");
                }
            }
            
            String token = tokenService.generateToken(user);
            AuthSession session = sessionService.createSession(user.getUserId(), token, ipAddress, deviceId, deviceInfo);
            
            userService.resetFailedLoginCount(user);
            
            auditService.log(user.getUserId(), "login", "success", ipAddress, deviceInfo, "登录成功");
            
            return new LoginResponse(token, session.getSessionId(), user.getUserId(), user.getUsername());
            
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            auditService.log(null, "login", "failure", ipAddress, deviceInfo, "登录异常: " + e.getMessage());
            throw new AuthException(500, "登录失败");
        }
    }
    
    public void logout(String token, HttpServletRequest httpRequest) {
        String ipAddress = getClientIp(httpRequest);
        String userId = tokenService.getUserIdFromToken(token);
        
        sessionService.invalidateSession(token);
        
        auditService.log(userId, "logout", "success", ipAddress, httpRequest.getHeader("User-Agent"), "登出成功");
    }
    
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}