package com.authcenter.service;

import com.authcenter.entity.AuthSession;
import com.authcenter.repository.AuthSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {
    
    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);
    
    @Autowired
    private AuthSessionRepository sessionRepository;
    
    @Autowired
    private SecurityPolicyService securityPolicyService;
    
    @Autowired
    private UserService userService;
    
    @Value("${session.max-duration:7200000}")
    private long sessionMaxDuration;
    
    public static class SessionBindingResult {
        private boolean valid;
        private String message;
        
        public SessionBindingResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public String getMessage() {
            return message;
        }
    }
    
    @Transactional
    public AuthSession createSession(String userId, String token, String ipAddress, String deviceId, String deviceInfo) {
        List<String> userRoles = userService.getUserRoles(userId);
        long maxDuration = securityPolicyService.getSessionMaxDurationForRoles(userRoles);
        
        AuthSession session = new AuthSession();
        session.setSessionId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        session.setUserId(userId);
        session.setToken(token);
        session.setCreatedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusNanos(maxDuration * 1000000));
        session.setIpAddress(ipAddress);
        session.setDeviceId(deviceId);
        session.setDeviceInfo(deviceInfo);
        session.setStatus("active");
        
        AuthSession savedSession = sessionRepository.save(session);
        logger.info("Created session {} for user {} from IP {} device {}", 
                savedSession.getSessionId(), userId, ipAddress, deviceId);
        return savedSession;
    }
    
    public AuthSession getSessionById(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElse(null);
    }
    
    public AuthSession getSessionByToken(String token) {
        return sessionRepository.findByToken(token)
                .orElse(null);
    }
    
    public String getSessionIdByToken(String token) {
        AuthSession session = sessionRepository.findByToken(token).orElse(null);
        return session != null ? session.getSessionId() : null;
    }
    
    public boolean isSessionValid(String token) {
        AuthSession session = getSessionByToken(token);
        if (session == null) {
            logger.debug("Session not found for token");
            return false;
        }
        if (!"active".equals(session.getStatus())) {
            logger.debug("Session {} is not active: {}", session.getSessionId(), session.getStatus());
            return false;
        }
        return session.getExpiresAt().isAfter(LocalDateTime.now());
    }
    
    public SessionBindingResult validateSessionBinding(String token, String currentIp, String currentDeviceInfo, String currentDeviceId) {
        AuthSession session = getSessionByToken(token);
        
        if (session == null) {
            return new SessionBindingResult(false, "会话不存在");
        }
        
        if (!isSessionValid(token)) {
            return new SessionBindingResult(false, "会话已过期或失效");
        }
        
        List<String> userRoles = userService.getUserRoles(session.getUserId());
        
        boolean ipCheckEnabled = securityPolicyService.isSessionIpCheckEnabledForRoles(userRoles);
        boolean deviceCheckEnabled = securityPolicyService.isSessionDeviceCheckEnabledForRoles(userRoles);
        boolean deviceIdCheckEnabled = securityPolicyService.isSessionDeviceIdCheckEnabledForRoles(userRoles);
        
        if (ipCheckEnabled) {
            String savedIp = session.getIpAddress();
            if (savedIp != null && !savedIp.equals(currentIp)) {
                logger.warn("IP mismatch for session {}: expected {}, got {}", 
                        session.getSessionId(), savedIp, currentIp);
                return new SessionBindingResult(false, "IP地址不匹配，拒绝访问");
            }
        }
        
        if (deviceCheckEnabled) {
            String savedDeviceInfo = session.getDeviceInfo();
            if (savedDeviceInfo != null && currentDeviceInfo != null) {
                if (!savedDeviceInfo.equals(currentDeviceInfo)) {
                    logger.warn("Device info mismatch for session {}: expected {}, got {}", 
                            session.getSessionId(), savedDeviceInfo, currentDeviceInfo);
                    return new SessionBindingResult(false, "设备信息不匹配，拒绝访问");
                }
            }
        }
        
        if (deviceIdCheckEnabled) {
            String savedDeviceId = session.getDeviceId();
            if (savedDeviceId != null && !savedDeviceId.equals(currentDeviceId)) {
                logger.warn("Device ID mismatch for session {}: expected {}, got {}", 
                        session.getSessionId(), savedDeviceId, currentDeviceId);
                return new SessionBindingResult(false, "设备ID不匹配，拒绝访问");
            }
        }
        
        if (ipCheckEnabled || deviceCheckEnabled || deviceIdCheckEnabled) {
            return new SessionBindingResult(true, "会话绑定验证通过");
        }
        
        return new SessionBindingResult(true, "会话验证通过（绑定检查已禁用）");
    }
    
    public SessionBindingResult validateSessionBindingFull(String token, String currentIp, String currentDeviceInfo, String currentDeviceId) {
        AuthSession session = getSessionByToken(token);
        
        if (session == null) {
            return new SessionBindingResult(false, "会话不存在");
        }
        
        if (!isSessionValid(token)) {
            return new SessionBindingResult(false, "会话已过期或失效");
        }
        
        String savedIp = session.getIpAddress();
        if (savedIp != null && !savedIp.equals(currentIp)) {
            logger.warn("Full binding check failed - IP mismatch for session {}: expected {}, got {}", 
                    session.getSessionId(), savedIp, currentIp);
            return new SessionBindingResult(false, "IP地址不匹配，拒绝访问");
        }
        
        String savedDeviceInfo = session.getDeviceInfo();
        if (savedDeviceInfo != null && currentDeviceInfo != null && !savedDeviceInfo.equals(currentDeviceInfo)) {
            logger.warn("Full binding check failed - Device info mismatch for session {}: expected {}, got {}", 
                    session.getSessionId(), savedDeviceInfo, currentDeviceInfo);
            return new SessionBindingResult(false, "设备信息不匹配，拒绝访问");
        }
        
        return new SessionBindingResult(true, "会话绑定验证通过（IP和设备信息一致）");
    }
    
    public boolean isBindingValid(String token, String currentIp, String currentDeviceInfo) {
        SessionBindingResult result = validateSessionBindingFull(token, currentIp, currentDeviceInfo, null);
        return result.isValid();
    }
    
    @Transactional
    public void invalidateSession(String token) {
        AuthSession session = sessionRepository.findByToken(token).orElse(null);
        if (session != null) {
            session.setStatus("expired");
            sessionRepository.save(session);
            logger.info("Invalidated session: {}", session.getSessionId());
        }
    }
    
    @Transactional
    public void invalidateUserSessions(String userId) {
        List<AuthSession> sessions = sessionRepository.findByUserIdAndStatus(userId, "active");
        for (AuthSession session : sessions) {
            session.setStatus("expired");
            sessionRepository.save(session);
        }
        logger.info("Invalidated all sessions for user: {}", userId);
    }
    
    @Transactional
    public AuthSession refreshSession(String sessionId) {
        Optional<AuthSession> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            AuthSession session = sessionOpt.get();
            if ("active".equals(session.getStatus()) && session.getExpiresAt().isAfter(LocalDateTime.now())) {
                List<String> userRoles = userService.getUserRoles(session.getUserId());
                long maxDuration = securityPolicyService.getSessionMaxDurationForRoles(userRoles);
                session.setExpiresAt(LocalDateTime.now().plusNanos(maxDuration * 1000000));
                AuthSession refreshed = sessionRepository.save(session);
                logger.info("Refreshed session: {}", sessionId);
                return refreshed;
            }
        }
        return null;
    }
}