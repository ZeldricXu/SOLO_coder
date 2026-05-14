package com.mobilestore.service;

import com.mobilestore.entity.UserRole;
import com.mobilestore.exception.PermissionDeniedException;
import com.mobilestore.repository.UserRoleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class ApprovalPermissionService {

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String PERMISSION_CACHE_PREFIX = "permission:";
    private static final long CACHE_TTL = 30;

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_REVIEWER = "reviewer";
    public static final String ROLE_DEVELOPER = "developer";
    public static final String ROLE_SUPPORT = "support";

    public static final String PERM_APPROVE_VERSION = "version:approve";
    public static final String PERM_REJECT_VERSION = "version:reject";
    public static final String PERM_VIEW_APPROVAL = "version:view_approval";
    public static final String PERM_SUBMIT_VERSION = "version:submit";
    public static final String PERM_PROCESS_FEEDBACK = "feedback:process";

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = new HashMap<>();

    static {
        Set<String> adminPerms = new HashSet<>();
        adminPerms.add(PERM_APPROVE_VERSION);
        adminPerms.add(PERM_REJECT_VERSION);
        adminPerms.add(PERM_VIEW_APPROVAL);
        adminPerms.add(PERM_SUBMIT_VERSION);
        adminPerms.add(PERM_PROCESS_FEEDBACK);
        ROLE_PERMISSIONS.put(ROLE_ADMIN, adminPerms);

        Set<String> reviewerPerms = new HashSet<>();
        reviewerPerms.add(PERM_APPROVE_VERSION);
        reviewerPerms.add(PERM_REJECT_VERSION);
        reviewerPerms.add(PERM_VIEW_APPROVAL);
        ROLE_PERMISSIONS.put(ROLE_REVIEWER, reviewerPerms);

        Set<String> devPerms = new HashSet<>();
        devPerms.add(PERM_SUBMIT_VERSION);
        devPerms.add(PERM_VIEW_APPROVAL);
        ROLE_PERMISSIONS.put(ROLE_DEVELOPER, devPerms);

        Set<String> supportPerms = new HashSet<>();
        supportPerms.add(PERM_PROCESS_FEEDBACK);
        ROLE_PERMISSIONS.put(ROLE_SUPPORT, supportPerms);
    }

    @PostConstruct
    public void initDefaultRoles() {
        if (userRoleRepository.count() == 0) {
            createDefaultRole("admin_001", "系统管理员", ROLE_ADMIN, "系统管理员");
            createDefaultRole("reviewer_001", "审批人员001", ROLE_REVIEWER, "版本审批人员");
            createDefaultRole("reviewer_002", "审批人员002", ROLE_REVIEWER, "版本审批人员");
            createDefaultRole("dev_001", "开发者001", ROLE_DEVELOPER, "应用开发者");
            createDefaultRole("tech_support_001", "技术支持001", ROLE_SUPPORT, "技术支持人员");
            createDefaultRole("product_001", "产品人员001", ROLE_SUPPORT, "产品人员");
        }
    }

    private void createDefaultRole(String userId, String userName, String roleCode, String roleName) {
        UserRole role = new UserRole();
        role.setId("role_" + UUID.randomUUID().toString().substring(0, 8));
        role.setUserId(userId);
        role.setUserName(userName);
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setStatus("active");
        try {
            role.setPermissions(objectMapper.writeValueAsString(ROLE_PERMISSIONS.getOrDefault(roleCode, new HashSet<>())));
        } catch (JsonProcessingException e) {
            role.setPermissions("[]");
        }
        userRoleRepository.save(role);
    }

    public boolean hasPermission(String userId, String permission) {
        if (userId == null || permission == null) {
            return false;
        }

        String cacheKey = PERMISSION_CACHE_PREFIX + userId;
        Object cachedPerms = redisTemplate.opsForValue().get(cacheKey);
        
        Set<String> userPerms;
        if (cachedPerms != null) {
            try {
                userPerms = objectMapper.readValue(
                    objectMapper.writeValueAsString(cachedPerms),
                    new TypeReference<Set<String>>() {}
                );
            } catch (Exception e) {
                userPerms = loadPermissionsFromDB(userId);
            }
        } else {
            userPerms = loadPermissionsFromDB(userId);
            if (!userPerms.isEmpty()) {
                try {
                    redisTemplate.opsForValue().set(cacheKey, userPerms, CACHE_TTL, TimeUnit.MINUTES);
                } catch (Exception e) {
                }
            }
        }

        return userPerms.contains(permission);
    }

    private Set<String> loadPermissionsFromDB(String userId) {
        Set<String> perms = new HashSet<>();
        
        Optional<UserRole> userRoleOpt = userRoleRepository.findActiveByUserId(userId);
        if (userRoleOpt.isPresent()) {
            UserRole userRole = userRoleOpt.get();
            
            Set<String> defaultPerms = ROLE_PERMISSIONS.getOrDefault(userRole.getRoleCode(), new HashSet<>());
            perms.addAll(defaultPerms);

            if (userRole.getPermissions() != null && !userRole.getPermissions().isEmpty()) {
                try {
                    Set<String> customPerms = objectMapper.readValue(
                        userRole.getPermissions(),
                        new TypeReference<Set<String>>() {}
                    );
                    perms.addAll(customPerms);
                } catch (Exception e) {
                }
            }
        }

        return perms;
    }

    public void checkApprovalPermission(String userId) {
        if (!hasPermission(userId, PERM_APPROVE_VERSION)) {
            throw new PermissionDeniedException("当前用户不具备审批权限，请联系管理员授权");
        }
    }

    public void checkRejectPermission(String userId) {
        if (!hasPermission(userId, PERM_REJECT_VERSION)) {
            throw new PermissionDeniedException("当前用户不具备拒绝权限，请联系管理员授权");
        }
    }

    public void checkSubmitPermission(String userId) {
        if (!hasPermission(userId, PERM_SUBMIT_VERSION)) {
            throw new PermissionDeniedException("当前用户不具备提交发布权限");
        }
    }

    public void checkFeedbackProcessPermission(String userId) {
        if (!hasPermission(userId, PERM_PROCESS_FEEDBACK)) {
            throw new PermissionDeniedException("当前用户不具备处理反馈权限");
        }
    }

    public boolean isReviewer(String userId) {
        return hasPermission(userId, PERM_APPROVE_VERSION);
    }

    public boolean isDeveloper(String userId) {
        return hasPermission(userId, PERM_SUBMIT_VERSION);
    }

    public String getUserRole(String userId) {
        Optional<UserRole> userRoleOpt = userRoleRepository.findActiveByUserId(userId);
        return userRoleOpt.map(UserRole::getRoleCode).orElse(null);
    }

    public void clearPermissionCache(String userId) {
        String cacheKey = PERMISSION_CACHE_PREFIX + userId;
        redisTemplate.delete(cacheKey);
    }

    public List<UserRole> getAllReviewers() {
        return userRoleRepository.findActiveByRoleCodes(
            Arrays.asList(ROLE_ADMIN, ROLE_REVIEWER)
        );
    }

    public UserRole getUserRoleInfo(String userId) {
        return userRoleRepository.findActiveByUserId(userId).orElse(null);
    }
}
