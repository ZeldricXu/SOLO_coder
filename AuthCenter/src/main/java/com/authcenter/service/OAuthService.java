package com.authcenter.service;

import com.authcenter.dto.LoginResponse;
import com.authcenter.dto.OAuthLoginRequest;
import com.authcenter.entity.AuthSession;
import com.authcenter.entity.OAuthBinding;
import com.authcenter.entity.User;
import com.authcenter.exception.AuthException;
import com.authcenter.repository.OAuthBindingRepository;
import com.authcenter.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OAuthService {
    
    @Autowired
    private OAuthBindingRepository oauthBindingRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private TokenService tokenService;
    
    @Autowired
    private SessionService sessionService;
    
    @Autowired
    private AuditService auditService;
    
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    
    @Transactional
    public LoginResponse loginWithOAuth(OAuthLoginRequest request, String ipAddress, String deviceInfo, String deviceId) {
        OAuthUserInfo userInfo = verifyOAuthToken(request.getProvider(), request.getOauthToken());
        
        OAuthBinding binding = oauthBindingRepository
                .findByProviderAndProviderUserId(request.getProvider(), userInfo.getProviderUserId())
                .orElse(null);
        
        User user;
        if (binding != null) {
            user = userRepository.findById(binding.getUserId())
                    .orElseThrow(() -> new AuthException(404, "绑定的用户不存在"));
        } else {
            user = createUserFromOAuth(userInfo, request.getProvider());
            binding = createOAuthBinding(user.getUserId(), request.getProvider(), userInfo.getProviderUserId());
        }
        
        if ("disabled".equals(user.getStatus())) {
            auditService.log(user.getUserId(), "oauth_login", "failure", ipAddress, deviceInfo, "用户已禁用");
            throw new AuthException(401, "用户已被禁用");
        }
        
        String token = tokenService.generateToken(user);
        AuthSession session = sessionService.createSession(user.getUserId(), token, ipAddress, deviceId, deviceInfo);
        
        auditService.log(user.getUserId(), "oauth_login", "success", ipAddress, deviceInfo, "OAuth登录成功, provider: " + request.getProvider());
        
        return new LoginResponse(token, session.getSessionId(), user.getUserId(), user.getUsername());
    }
    
    @Transactional
    public OAuthBinding bindOAuth(String userId, String provider, String oauthToken) {
        OAuthUserInfo userInfo = verifyOAuthToken(provider, oauthToken);
        
        if (oauthBindingRepository.findByProviderAndProviderUserId(provider, userInfo.getProviderUserId()).isPresent()) {
            throw new AuthException(400, "该OAuth账号已绑定到其他用户");
        }
        
        if (oauthBindingRepository.findByUserIdAndProvider(userId, provider).isPresent()) {
            throw new AuthException(400, "该用户已绑定相同类型的OAuth账号");
        }
        
        return createOAuthBinding(userId, provider, userInfo.getProviderUserId());
    }
    
    private OAuthUserInfo verifyOAuthToken(String provider, String oauthToken) {
        Map<String, OAuthUserInfo> mockUsers = new HashMap<>();
        mockUsers.put("wechat", new OAuthUserInfo("wechat_" + oauthToken.hashCode(), 
                "wechat_user_" + oauthToken.hashCode(), 
                "wechat_user_" + oauthToken.hashCode() + "@oauth.com"));
        mockUsers.put("github", new OAuthUserInfo("github_" + oauthToken.hashCode(), 
                "github_user_" + oauthToken.hashCode(), 
                "github_user_" + oauthToken.hashCode() + "@oauth.com"));
        mockUsers.put("google", new OAuthUserInfo("google_" + oauthToken.hashCode(), 
                "google_user_" + oauthToken.hashCode(), 
                "google_user_" + oauthToken.hashCode() + "@oauth.com"));
        
        OAuthUserInfo info = mockUsers.get(provider);
        if (info == null) {
            info = new OAuthUserInfo(provider + "_" + oauthToken.hashCode(), 
                    provider + "_user_" + oauthToken.hashCode(), 
                    provider + "_user_" + oauthToken.hashCode() + "@oauth.com");
        }
        
        return info;
    }
    
    private User createUserFromOAuth(OAuthUserInfo userInfo, String provider) {
        User user = new User();
        user.setUserId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        user.setUsername(userInfo.getUsername());
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setEmail(userInfo.getEmail());
        user.setMfaEnabled(false);
        user.setStatus("active");
        user.setCreatedAt(LocalDateTime.now());
        user.setFailedLoginCount(0);
        return userRepository.save(user);
    }
    
    private OAuthBinding createOAuthBinding(String userId, String provider, String providerUserId) {
        OAuthBinding binding = new OAuthBinding();
        binding.setOauthId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        binding.setUserId(userId);
        binding.setProvider(provider);
        binding.setProviderUserId(providerUserId);
        binding.setBindTime(LocalDateTime.now());
        binding.setStatus("active");
        return oauthBindingRepository.save(binding);
    }
    
    public static class OAuthUserInfo {
        private String providerUserId;
        private String username;
        private String email;
        
        public OAuthUserInfo() {
        }
        
        public OAuthUserInfo(String providerUserId, String username, String email) {
            this.providerUserId = providerUserId;
            this.username = username;
            this.email = email;
        }
        
        public String getProviderUserId() {
            return providerUserId;
        }
        
        public void setProviderUserId(String providerUserId) {
            this.providerUserId = providerUserId;
        }
        
        public String getUsername() {
            return username;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
        
        public String getEmail() {
            return email;
        }
        
        public void setEmail(String email) {
            this.email = email;
        }
    }
}