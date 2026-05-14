package com.authcenter.service;

import com.authcenter.entity.UserRole;
import com.authcenter.exception.AuthException;
import com.authcenter.repository.UserRoleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class PermissionService {
    
    @Autowired
    private UserRoleRepository userRoleRepository;
    
    @Autowired
    private TokenService tokenService;
    
    public List<String> getUserRoles(String userId) {
        List<UserRole> roles = userRoleRepository.findByUserId(userId);
        return roles.stream()
                .map(UserRole::getRole)
                .collect(Collectors.toList());
    }
    
    public boolean hasRole(String userId, String role) {
        List<String> roles = getUserRoles(userId);
        return roles.contains(role);
    }
    
    public boolean hasAnyRole(String userId, String... roles) {
        List<String> userRoles = getUserRoles(userId);
        for (String role : roles) {
            if (userRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }
    
    public boolean hasAllRoles(String userId, String... roles) {
        List<String> userRoles = getUserRoles(userId);
        for (String role : roles) {
            if (!userRoles.contains(role)) {
                return false;
            }
        }
        return true;
    }
    
    public void checkPermission(String token, String requiredRole) {
        if (!tokenService.isTokenValid(token)) {
            throw new AuthException(401, "无效的令牌");
        }
        
        String userId = tokenService.getUserIdFromToken(token);
        if (!hasRole(userId, requiredRole)) {
            throw new AuthException(403, "没有足够的权限");
        }
    }
    
    public boolean isAdmin(String userId) {
        return hasRole(userId, "ADMIN");
    }
    
    public boolean isUser(String userId) {
        return hasRole(userId, "USER");
    }
}