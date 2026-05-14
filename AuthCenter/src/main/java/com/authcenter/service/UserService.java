package com.authcenter.service;

import com.authcenter.dto.CreateUserRequest;
import com.authcenter.entity.User;
import com.authcenter.entity.UserRole;
import com.authcenter.exception.AuthException;
import com.authcenter.repository.UserRepository;
import com.authcenter.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserService {
    
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private UserRoleRepository userRoleRepository;
    
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    
    @Autowired
    private SecurityPolicyService securityPolicyService;
    
    @Transactional
    public User createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new AuthException(400, "用户名已存在");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AuthException(400, "邮箱已存在");
        }
        
        if (!securityPolicyService.validatePassword(request.getPassword())) {
            throw new AuthException(400, "密码不符合安全策略要求");
        }
        
        User user = new User();
        user.setUserId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setMfaEnabled(request.getMfaEnabled());
        user.setMfaType(request.getMfaType());
        user.setStatus("active");
        user.setCreatedAt(LocalDateTime.now());
        user.setFailedLoginCount(0);
        
        User savedUser = userRepository.save(user);
        
        UserRole defaultRole = new UserRole();
        defaultRole.setRoleId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        defaultRole.setUserId(savedUser.getUserId());
        defaultRole.setRole("USER");
        defaultRole.setCreatedAt(LocalDateTime.now());
        userRoleRepository.save(defaultRole);
        
        logger.info("Created new user: {}", savedUser.getUsername());
        return savedUser;
    }
    
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new AuthException(404, "用户不存在"));
    }
    
    public User getUserById(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AuthException(404, "用户不存在"));
    }
    
    public List<String> getUserRoles(String userId) {
        List<UserRole> roles = userRoleRepository.findByUserId(userId);
        return roles.stream()
                .map(UserRole::getRole)
                .collect(Collectors.toList());
    }
    
    @Transactional
    public void updateFailedLoginCount(User user) {
        int newCount = user.getFailedLoginCount() + 1;
        user.setFailedLoginCount(newCount);
        
        List<String> userRoles = getUserRoles(user.getUserId());
        
        if (securityPolicyService.shouldLockAccountForRoles(newCount, userRoles)) {
            long lockDuration = securityPolicyService.getLockDurationForRoles(userRoles);
            user.setLockedUntil(LocalDateTime.now().plusNanos(lockDuration * 1000000));
            user.setStatus("locked");
            
            logger.warn("Account locked for user {} (roles: {}) for {} ms due to {} failed attempts",
                    user.getUsername(), userRoles, lockDuration, newCount);
        }
        
        userRepository.save(user);
    }
    
    @Transactional
    public void resetFailedLoginCount(User user) {
        user.setFailedLoginCount(0);
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
        
        logger.debug("Reset failed login count for user: {}", user.getUsername());
    }
    
    public boolean isAccountLocked(User user) {
        if (user.getStatus().equals("locked")) {
            if (user.getLockedUntil() != null && 
                user.getLockedUntil().isBefore(LocalDateTime.now())) {
                user.setStatus("active");
                user.setLockedUntil(null);
                userRepository.save(user);
                logger.info("Account unlocked for user: {}", user.getUsername());
                return false;
            }
            return true;
        }
        return false;
    }
    
    @Transactional
    public User updateUserMfa(String userId, boolean enabled, String mfaType) {
        User user = getUserById(userId);
        user.setMfaEnabled(enabled);
        user.setMfaType(mfaType);
        return userRepository.save(user);
    }
    
    public boolean verifyPassword(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
    
    @Transactional
    public UserRole addRoleToUser(String userId, String role) {
        User user = getUserById(userId);
        
        UserRole userRole = new UserRole();
        userRole.setRoleId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        userRole.setUserId(userId);
        userRole.setRole(role);
        userRole.setCreatedAt(LocalDateTime.now());
        
        logger.info("Added role {} to user {}", role, user.getUsername());
        return userRoleRepository.save(userRole);
    }
    
    @Transactional
    public void removeRoleFromUser(String userId, String role) {
        List<UserRole> roles = userRoleRepository.findByUserId(userId);
        for (UserRole userRole : roles) {
            if (userRole.getRole().equals(role)) {
                userRoleRepository.delete(userRole);
                logger.info("Removed role {} from user {}", role, userId);
            }
        }
    }
}