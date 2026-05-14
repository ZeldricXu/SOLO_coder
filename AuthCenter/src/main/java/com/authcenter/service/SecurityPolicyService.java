package com.authcenter.service;

import com.authcenter.entity.SecurityPolicy;
import com.authcenter.repository.SecurityPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SecurityPolicyService {
    
    private static final Logger logger = LoggerFactory.getLogger(SecurityPolicyService.class);
    
    @Autowired
    private SecurityPolicyRepository policyRepository;
    
    private final Map<String, SecurityPolicy> policyCache = new ConcurrentHashMap<>();
    private volatile long lastCacheRefreshTime = 0;
    private static final long CACHE_REFRESH_INTERVAL = 60000;
    
    @Value("${security.policy.max-failed-login:5}")
    private int defaultMaxFailedLogin;
    
    @Value("${security.policy.lock-duration:300000}")
    private long defaultLockDuration;
    
    @Value("${security.policy.password-min-length:8}")
    private int defaultPasswordMinLength;
    
    @Value("${security.policy.password-require-complex:true}")
    private boolean defaultPasswordRequireComplex;
    
    @Value("${security.policy.admin-lock-duration:1800000}")
    private long defaultAdminLockDuration;
    
    @Value("${security.policy.admin-max-failed-login:3}")
    private int defaultAdminMaxFailedLogin;
    
    @Value("${session.max-duration:7200000}")
    private long defaultSessionMaxDuration;
    
    @Value("${security.policy.session-ip-check:true}")
    private boolean defaultSessionIpCheck;
    
    @Value("${security.policy.session-device-check:true}")
    private boolean defaultSessionDeviceCheck;
    
    @Value("${jwt.expiration:7200000}")
    private long defaultTokenExpiration;
    
    private static final Pattern UPPER_CASE = Pattern.compile("[A-Z]");
    private static final Pattern LOWER_CASE = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL_CHAR = Pattern.compile("[!@#$%^&*(),.?\":{}|<>]");
    
    private static final String POLICY_TYPE_LOGIN = "login_limit";
    private static final String POLICY_TYPE_PASSWORD = "password_policy";
    private static final String POLICY_TYPE_SESSION = "session";
    private static final String POLICY_TYPE_TOKEN = "token";
    private static final String POLICY_TYPE_GENERAL = "general";
    
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";
    
    public SecurityPolicy getPolicy(String policyType) {
        return getPolicyForRoles(policyType, Collections.singletonList(ROLE_USER));
    }
    
    public SecurityPolicy getPolicyForRole(String policyType, String role) {
        return getPolicyForRoles(policyType, Collections.singletonList(role));
    }
    
    public SecurityPolicy getPolicyForRoles(String policyType, List<String> roles) {
        refreshCacheIfNeeded();
        
        String cacheKey = buildCacheKey(policyType, roles);
        if (policyCache.containsKey(cacheKey)) {
            return policyCache.get(cacheKey);
        }
        
        List<SecurityPolicy> policies = policyRepository
                .findByPolicyTypeAndRoleNameInAndEnabledTrueOrderByPriorityDesc(policyType, roles);
        
        if (policies.isEmpty()) {
            SecurityPolicy defaultPolicy = buildDefaultPolicyForRoles(policyType, roles);
            policyCache.put(cacheKey, defaultPolicy);
            return defaultPolicy;
        }
        
        SecurityPolicy mergedPolicy = mergePolicies(policies, policyType, roles);
        policyCache.put(cacheKey, mergedPolicy);
        return mergedPolicy;
    }
    
    private SecurityPolicy mergePolicies(List<SecurityPolicy> policies, String policyType, List<String> roles) {
        if (policies.size() == 1) {
            return policies.get(0);
        }
        
        SecurityPolicy base = getDefaultPolicy(policyType);
        
        for (SecurityPolicy policy : policies) {
            base = mergeTwoPolicies(base, policy);
        }
        
        return base;
    }
    
    private SecurityPolicy mergeTwoPolicies(SecurityPolicy base, SecurityPolicy override) {
        if (override.getMaxFailedLogin() != null) {
            base.setMaxFailedLogin(override.getMaxFailedLogin());
        }
        if (override.getLockDuration() != null) {
            base.setLockDuration(override.getLockDuration());
        }
        if (override.getPasswordMinLength() != null) {
            base.setPasswordMinLength(override.getPasswordMinLength());
        }
        if (override.getPasswordRequireComplex() != null) {
            base.setPasswordRequireComplex(override.getPasswordRequireComplex());
        }
        if (override.getPasswordRequireUpper() != null) {
            base.setPasswordRequireUpper(override.getPasswordRequireUpper());
        }
        if (override.getPasswordRequireLower() != null) {
            base.setPasswordRequireLower(override.getPasswordRequireLower());
        }
        if (override.getPasswordRequireDigit() != null) {
            base.setPasswordRequireDigit(override.getPasswordRequireDigit());
        }
        if (override.getPasswordRequireSpecial() != null) {
            base.setPasswordRequireSpecial(override.getPasswordRequireSpecial());
        }
        if (override.getSessionMaxDuration() != null) {
            base.setSessionMaxDuration(override.getSessionMaxDuration());
        }
        if (override.getSessionIpCheck() != null) {
            base.setSessionIpCheck(override.getSessionIpCheck());
        }
        if (override.getSessionDeviceCheck() != null) {
            base.setSessionDeviceCheck(override.getSessionDeviceCheck());
        }
        if (override.getSessionDeviceIdCheck() != null) {
            base.setSessionDeviceIdCheck(override.getSessionDeviceIdCheck());
        }
        if (override.getTokenExpiration() != null) {
            base.setTokenExpiration(override.getTokenExpiration());
        }
        if (override.getMfaRequired() != null) {
            base.setMfaRequired(override.getMfaRequired());
        }
        return base;
    }
    
    private String buildCacheKey(String policyType, List<String> roles) {
        List<String> sortedRoles = new ArrayList<>(roles);
        Collections.sort(sortedRoles);
        return policyType + ":" + String.join(",", sortedRoles);
    }
    
    private SecurityPolicy buildDefaultPolicyForRoles(String policyType, List<String> roles) {
        SecurityPolicy policy = getDefaultPolicy(policyType);
        
        if (roles.contains(ROLE_ADMIN)) {
            policy.setMaxFailedLogin(defaultAdminMaxFailedLogin);
            policy.setLockDuration(defaultAdminLockDuration);
            policy.setMfaRequired(true);
        }
        
        return policy;
    }
    
    private void refreshCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCacheRefreshTime > CACHE_REFRESH_INTERVAL) {
            synchronized (this) {
                if (now - lastCacheRefreshTime > CACHE_REFRESH_INTERVAL) {
                    policyCache.clear();
                    lastCacheRefreshTime = now;
                    logger.debug("Security policy cache refreshed");
                }
            }
        }
    }
    
    public void refreshPolicyCache() {
        policyCache.clear();
        lastCacheRefreshTime = System.currentTimeMillis();
        logger.info("Security policy cache manually refreshed");
    }
    
    public int getMaxFailedLogin() {
        SecurityPolicy policy = getPolicy(POLICY_TYPE_LOGIN);
        return policy.getMaxFailedLogin() != null ? policy.getMaxFailedLogin() : defaultMaxFailedLogin;
    }
    
    public int getMaxFailedLoginForRole(String role) {
        SecurityPolicy policy = getPolicyForRole(POLICY_TYPE_LOGIN, role);
        return policy.getMaxFailedLogin() != null ? policy.getMaxFailedLogin() : 
               (ROLE_ADMIN.equals(role) ? defaultAdminMaxFailedLogin : defaultMaxFailedLogin);
    }
    
    public int getMaxFailedLoginForRoles(List<String> roles) {
        SecurityPolicy policy = getPolicyForRoles(POLICY_TYPE_LOGIN, roles);
        if (policy.getMaxFailedLogin() != null) {
            return policy.getMaxFailedLogin();
        }
        if (roles.contains(ROLE_ADMIN)) {
            return defaultAdminMaxFailedLogin;
        }
        return defaultMaxFailedLogin;
    }
    
    public long getLockDuration() {
        SecurityPolicy policy = getPolicy(POLICY_TYPE_LOGIN);
        return policy.getLockDuration() != null ? policy.getLockDuration() : defaultLockDuration;
    }
    
    public long getLockDurationForRole(String role) {
        SecurityPolicy policy = getPolicyForRole(POLICY_TYPE_LOGIN, role);
        return policy.getLockDuration() != null ? policy.getLockDuration() : 
               (ROLE_ADMIN.equals(role) ? defaultAdminLockDuration : defaultLockDuration);
    }
    
    public long getLockDurationForRoles(List<String> roles) {
        SecurityPolicy policy = getPolicyForRoles(POLICY_TYPE_LOGIN, roles);
        if (policy.getLockDuration() != null) {
            return policy.getLockDuration();
        }
        if (roles.contains(ROLE_ADMIN)) {
            return defaultAdminLockDuration;
        }
        return defaultLockDuration;
    }
    
    public int getPasswordMinLength() {
        SecurityPolicy policy = getPolicy(POLICY_TYPE_PASSWORD);
        return policy.getPasswordMinLength() != null ? policy.getPasswordMinLength() : defaultPasswordMinLength;
    }
    
    public int getPasswordMinLengthForRole(String role) {
        SecurityPolicy policy = getPolicyForRole(POLICY_TYPE_PASSWORD, role);
        return policy.getPasswordMinLength() != null ? policy.getPasswordMinLength() : defaultPasswordMinLength;
    }
    
    public boolean isPasswordRequireComplex() {
        SecurityPolicy policy = getPolicy(POLICY_TYPE_PASSWORD);
        return policy.getPasswordRequireComplex() != null ? policy.getPasswordRequireComplex() : defaultPasswordRequireComplex;
    }
    
    public boolean isPasswordRequireComplexForRole(String role) {
        SecurityPolicy policy = getPolicyForRole(POLICY_TYPE_PASSWORD, role);
        return policy.getPasswordRequireComplex() != null ? policy.getPasswordRequireComplex() : defaultPasswordRequireComplex;
    }
    
    public long getSessionMaxDuration() {
        SecurityPolicy policy = getPolicy(POLICY_TYPE_SESSION);
        return policy.getSessionMaxDuration() != null ? policy.getSessionMaxDuration() : defaultSessionMaxDuration;
    }
    
    public long getSessionMaxDurationForRole(String role) {
        SecurityPolicy policy = getPolicyForRole(POLICY_TYPE_SESSION, role);
        return policy.getSessionMaxDuration() != null ? policy.getSessionMaxDuration() : defaultSessionMaxDuration;
    }
    
    public boolean isSessionIpCheckEnabled() {
        SecurityPolicy policy = getPolicy(POLICY_TYPE_SESSION);
        return policy.getSessionIpCheck() != null ? policy.getSessionIpCheck() : defaultSessionIpCheck;
    }
    
    public boolean isSessionIpCheckEnabledForRole(String role) {
        SecurityPolicy policy = getPolicyForRole(POLICY_TYPE_SESSION, role);
        return policy.getSessionIpCheck() != null ? policy.getSessionIpCheck() : defaultSessionIpCheck;
    }
    
    public boolean isSessionIpCheckEnabledForRoles(List<String> roles) {
        SecurityPolicy policy = getPolicyForRoles(POLICY_TYPE_SESSION, roles);
        return policy.getSessionIpCheck() != null ? policy.getSessionIpCheck() : defaultSessionIpCheck;
    }
    
    public boolean isSessionDeviceCheckEnabled() {
        SecurityPolicy policy = getPolicy(POLICY_TYPE_SESSION);
        return policy.getSessionDeviceCheck() != null ? policy.getSessionDeviceCheck() : defaultSessionDeviceCheck;
    }
    
    public boolean isSessionDeviceCheckEnabledForRole(String role) {
        SecurityPolicy policy = getPolicyForRole(POLICY_TYPE_SESSION, role);
        return policy.getSessionDeviceCheck() != null ? policy.getSessionDeviceCheck() : defaultSessionDeviceCheck;
    }
    
    public boolean isSessionDeviceCheckEnabledForRoles(List<String> roles) {
        SecurityPolicy policy = getPolicyForRoles(POLICY_TYPE_SESSION, roles);
        return policy.getSessionDeviceCheck() != null ? policy.getSessionDeviceCheck() : defaultSessionDeviceCheck;
    }
    
    public boolean isSessionDeviceIdCheckEnabledForRoles(List<String> roles) {
        SecurityPolicy policy = getPolicyForRoles(POLICY_TYPE_SESSION, roles);
        return policy.getSessionDeviceIdCheck() != null ? policy.getSessionDeviceIdCheck() : false;
    }
    
    public long getTokenExpirationForRoles(List<String> roles) {
        SecurityPolicy policy = getPolicyForRoles(POLICY_TYPE_TOKEN, roles);
        return policy.getTokenExpiration() != null ? policy.getTokenExpiration() : defaultTokenExpiration;
    }
    
    public boolean shouldLockAccount(int failedLoginCount) {
        return failedLoginCount >= getMaxFailedLogin();
    }
    
    public boolean shouldLockAccountForRole(int failedLoginCount, String role) {
        return failedLoginCount >= getMaxFailedLoginForRole(role);
    }
    
    public boolean shouldLockAccountForRoles(int failedLoginCount, List<String> roles) {
        return failedLoginCount >= getMaxFailedLoginForRoles(roles);
    }
    
    public boolean validatePassword(String password) {
        return validatePasswordForRoles(password, Collections.singletonList(ROLE_USER));
    }
    
    public boolean validatePasswordForRole(String password, String role) {
        return validatePasswordForRoles(password, Collections.singletonList(role));
    }
    
    public boolean validatePasswordForRoles(String password, List<String> roles) {
        if (password == null) {
            return false;
        }
        
        SecurityPolicy policy = getPolicyForRoles(POLICY_TYPE_PASSWORD, roles);
        
        int minLength = policy.getPasswordMinLength() != null ? 
                        policy.getPasswordMinLength() : defaultPasswordMinLength;
        
        if (password.length() < minLength) {
            return false;
        }
        
        if (Boolean.TRUE.equals(policy.getPasswordRequireComplex()) || defaultPasswordRequireComplex) {
            boolean hasUpper = UPPER_CASE.matcher(password).find();
            boolean hasLower = LOWER_CASE.matcher(password).find();
            boolean hasDigit = DIGIT.matcher(password).find();
            boolean hasSpecial = SPECIAL_CHAR.matcher(password).find();
            
            if (policy.getPasswordRequireUpper() != null && policy.getPasswordRequireUpper() && !hasUpper) {
                return false;
            }
            if (policy.getPasswordRequireLower() != null && policy.getPasswordRequireLower() && !hasLower) {
                return false;
            }
            if (policy.getPasswordRequireDigit() != null && policy.getPasswordRequireDigit() && !hasDigit) {
                return false;
            }
            if (policy.getPasswordRequireSpecial() != null && policy.getPasswordRequireSpecial() && !hasSpecial) {
                return false;
            }
            
            if (policy.getPasswordRequireComplex() != null && policy.getPasswordRequireComplex()) {
                return hasUpper && hasLower && hasDigit && hasSpecial;
            }
            
            if (defaultPasswordRequireComplex) {
                return hasUpper && hasLower && hasDigit && hasSpecial;
            }
        }
        
        return true;
    }
    
    private SecurityPolicy getDefaultPolicy(String policyType) {
        SecurityPolicy policy = new SecurityPolicy();
        policy.setPolicyId("default_" + policyType);
        policy.setPolicyType(policyType);
        policy.setPolicyName("Default " + policyType + " Policy");
        policy.setMaxFailedLogin(defaultMaxFailedLogin);
        policy.setLockDuration(defaultLockDuration);
        policy.setPasswordMinLength(defaultPasswordMinLength);
        policy.setPasswordRequireComplex(defaultPasswordRequireComplex);
        policy.setSessionMaxDuration(defaultSessionMaxDuration);
        policy.setSessionIpCheck(defaultSessionIpCheck);
        policy.setSessionDeviceCheck(defaultSessionDeviceCheck);
        policy.setSessionDeviceIdCheck(false);
        policy.setTokenExpiration(defaultTokenExpiration);
        policy.setMfaRequired(false);
        policy.setEnabled(true);
        policy.setPriority(0);
        return policy;
    }
    
    public List<SecurityPolicy> getAllPolicies() {
        return policyRepository.findByEnabledTrue();
    }
    
    public SecurityPolicy createPolicy(SecurityPolicy policy) {
        SecurityPolicy saved = policyRepository.save(policy);
        refreshPolicyCache();
        logger.info("Created new security policy: {}", policy.getPolicyName());
        return saved;
    }
    
    public SecurityPolicy updatePolicy(String policyId, SecurityPolicy policy) {
        SecurityPolicy existing = policyRepository.findById(policyId)
                .orElseThrow(() -> new RuntimeException("Policy not found: " + policyId));
        
        if (policy.getPolicyName() != null) existing.setPolicyName(policy.getPolicyName());
        if (policy.getRoleName() != null) existing.setRoleName(policy.getRoleName());
        if (policy.getMaxFailedLogin() != null) existing.setMaxFailedLogin(policy.getMaxFailedLogin());
        if (policy.getLockDuration() != null) existing.setLockDuration(policy.getLockDuration());
        if (policy.getPasswordMinLength() != null) existing.setPasswordMinLength(policy.getPasswordMinLength());
        if (policy.getPasswordRequireComplex() != null) existing.setPasswordRequireComplex(policy.getPasswordRequireComplex());
        if (policy.getPasswordRequireUpper() != null) existing.setPasswordRequireUpper(policy.getPasswordRequireUpper());
        if (policy.getPasswordRequireLower() != null) existing.setPasswordRequireLower(policy.getPasswordRequireLower());
        if (policy.getPasswordRequireDigit() != null) existing.setPasswordRequireDigit(policy.getPasswordRequireDigit());
        if (policy.getPasswordRequireSpecial() != null) existing.setPasswordRequireSpecial(policy.getPasswordRequireSpecial());
        if (policy.getSessionMaxDuration() != null) existing.setSessionMaxDuration(policy.getSessionMaxDuration());
        if (policy.getSessionIpCheck() != null) existing.setSessionIpCheck(policy.getSessionIpCheck());
        if (policy.getSessionDeviceCheck() != null) existing.setSessionDeviceCheck(policy.getSessionDeviceCheck());
        if (policy.getSessionDeviceIdCheck() != null) existing.setSessionDeviceIdCheck(policy.getSessionDeviceIdCheck());
        if (policy.getTokenExpiration() != null) existing.setTokenExpiration(policy.getTokenExpiration());
        if (policy.getMfaRequired() != null) existing.setMfaRequired(policy.getMfaRequired());
        if (policy.getPriority() != null) existing.setPriority(policy.getPriority());
        if (policy.getEnabled() != null) existing.setEnabled(policy.getEnabled());
        
        SecurityPolicy saved = policyRepository.save(existing);
        refreshPolicyCache();
        logger.info("Updated security policy: {}", policy.getPolicyName());
        return saved;
    }
    
    public void deletePolicy(String policyId) {
        policyRepository.deleteById(policyId);
        refreshPolicyCache();
        logger.info("Deleted security policy: {}", policyId);
    }
}