package com.authcenter.config;

import com.authcenter.entity.SecurityPolicy;
import com.authcenter.entity.User;
import com.authcenter.entity.UserRole;
import com.authcenter.repository.SecurityPolicyRepository;
import com.authcenter.repository.UserRepository;
import com.authcenter.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class DataInitializer implements CommandLineRunner {
    
    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private UserRoleRepository userRoleRepository;
    
    @Autowired
    private SecurityPolicyRepository policyRepository;
    
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    
    @Override
    public void run(String... args) {
        initSecurityPolicies();
        initDefaultAdmin();
    }
    
    private void initSecurityPolicies() {
        if (policyRepository.count() == 0) {
            logger.info("Initializing security policies...");
            
            SecurityPolicy defaultLoginPolicy = new SecurityPolicy();
            defaultLoginPolicy.setPolicyId("policy_login_default");
            defaultLoginPolicy.setPolicyName("Default Login Policy");
            defaultLoginPolicy.setPolicyType("login_limit");
            defaultLoginPolicy.setRoleName(null);
            defaultLoginPolicy.setMaxFailedLogin(5);
            defaultLoginPolicy.setLockDuration(300000L);
            defaultLoginPolicy.setPriority(0);
            defaultLoginPolicy.setEnabled(true);
            policyRepository.save(defaultLoginPolicy);
            logger.info("Created default login policy: maxFailedLogin=5, lockDuration=300000ms");
            
            SecurityPolicy adminLoginPolicy = new SecurityPolicy();
            adminLoginPolicy.setPolicyId("policy_login_admin");
            adminLoginPolicy.setPolicyName("Admin Login Policy");
            adminLoginPolicy.setPolicyType("login_limit");
            adminLoginPolicy.setRoleName("ADMIN");
            adminLoginPolicy.setMaxFailedLogin(3);
            adminLoginPolicy.setLockDuration(1800000L);
            adminLoginPolicy.setPriority(100);
            adminLoginPolicy.setEnabled(true);
            policyRepository.save(adminLoginPolicy);
            logger.info("Created admin login policy: maxFailedLogin=3, lockDuration=1800000ms");
            
            SecurityPolicy defaultPasswordPolicy = new SecurityPolicy();
            defaultPasswordPolicy.setPolicyId("policy_password_default");
            defaultPasswordPolicy.setPolicyName("Default Password Policy");
            defaultPasswordPolicy.setPolicyType("password_policy");
            defaultPasswordPolicy.setRoleName(null);
            defaultPasswordPolicy.setPasswordMinLength(8);
            defaultPasswordPolicy.setPasswordRequireComplex(true);
            defaultPasswordPolicy.setPasswordRequireUpper(true);
            defaultPasswordPolicy.setPasswordRequireLower(true);
            defaultPasswordPolicy.setPasswordRequireDigit(true);
            defaultPasswordPolicy.setPasswordRequireSpecial(true);
            defaultPasswordPolicy.setPriority(0);
            defaultPasswordPolicy.setEnabled(true);
            policyRepository.save(defaultPasswordPolicy);
            logger.info("Created default password policy: minLength=8, complex=true");
            
            SecurityPolicy adminPasswordPolicy = new SecurityPolicy();
            adminPasswordPolicy.setPolicyId("policy_password_admin");
            adminPasswordPolicy.setPolicyName("Admin Password Policy");
            adminPasswordPolicy.setPolicyType("password_policy");
            adminPasswordPolicy.setRoleName("ADMIN");
            adminPasswordPolicy.setPasswordMinLength(12);
            adminPasswordPolicy.setPasswordRequireComplex(true);
            adminPasswordPolicy.setPasswordRequireUpper(true);
            adminPasswordPolicy.setPasswordRequireLower(true);
            adminPasswordPolicy.setPasswordRequireDigit(true);
            adminPasswordPolicy.setPasswordRequireSpecial(true);
            adminPasswordPolicy.setMfaRequired(true);
            adminPasswordPolicy.setPriority(100);
            adminPasswordPolicy.setEnabled(true);
            policyRepository.save(adminPasswordPolicy);
            logger.info("Created admin password policy: minLength=12, mfaRequired=true");
            
            SecurityPolicy defaultSessionPolicy = new SecurityPolicy();
            defaultSessionPolicy.setPolicyId("policy_session_default");
            defaultSessionPolicy.setPolicyName("Default Session Policy");
            defaultSessionPolicy.setPolicyType("session");
            defaultSessionPolicy.setRoleName(null);
            defaultSessionPolicy.setSessionMaxDuration(7200000L);
            defaultSessionPolicy.setSessionIpCheck(true);
            defaultSessionPolicy.setSessionDeviceCheck(true);
            defaultSessionPolicy.setSessionDeviceIdCheck(false);
            defaultSessionPolicy.setPriority(0);
            defaultSessionPolicy.setEnabled(true);
            policyRepository.save(defaultSessionPolicy);
            logger.info("Created default session policy: ipCheck=true, deviceCheck=true");
            
            SecurityPolicy adminSessionPolicy = new SecurityPolicy();
            adminSessionPolicy.setPolicyId("policy_session_admin");
            adminSessionPolicy.setPolicyName("Admin Session Policy");
            adminSessionPolicy.setPolicyType("session");
            adminSessionPolicy.setRoleName("ADMIN");
            adminSessionPolicy.setSessionMaxDuration(3600000L);
            adminSessionPolicy.setSessionIpCheck(true);
            adminSessionPolicy.setSessionDeviceCheck(true);
            adminSessionPolicy.setSessionDeviceIdCheck(true);
            adminSessionPolicy.setPriority(100);
            adminSessionPolicy.setEnabled(true);
            policyRepository.save(adminSessionPolicy);
            logger.info("Created admin session policy: maxDuration=3600000ms, deviceIdCheck=true");
            
            SecurityPolicy defaultTokenPolicy = new SecurityPolicy();
            defaultTokenPolicy.setPolicyId("policy_token_default");
            defaultTokenPolicy.setPolicyName("Default Token Policy");
            defaultTokenPolicy.setPolicyType("token");
            defaultTokenPolicy.setRoleName(null);
            defaultTokenPolicy.setTokenExpiration(7200000L);
            defaultTokenPolicy.setPriority(0);
            defaultTokenPolicy.setEnabled(true);
            policyRepository.save(defaultTokenPolicy);
            logger.info("Created default token policy: expiration=7200000ms");
            
            SecurityPolicy adminTokenPolicy = new SecurityPolicy();
            adminTokenPolicy.setPolicyId("policy_token_admin");
            adminTokenPolicy.setPolicyName("Admin Token Policy");
            adminTokenPolicy.setPolicyType("token");
            adminTokenPolicy.setRoleName("ADMIN");
            adminTokenPolicy.setTokenExpiration(1800000L);
            adminTokenPolicy.setPriority(100);
            adminTokenPolicy.setEnabled(true);
            policyRepository.save(adminTokenPolicy);
            logger.info("Created admin token policy: expiration=1800000ms");
            
            logger.info("Security policies initialized successfully");
        } else {
            logger.info("Security policies already exist, skipping initialization");
        }
    }
    
    private void initDefaultAdmin() {
        if (!userRepository.existsByUsername("admin")) {
            logger.info("Creating default admin user...");
            
            User admin = new User();
            admin.setUserId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("Admin@123"));
            admin.setEmail("admin@authcenter.com");
            admin.setPhone("13800000001");
            admin.setMfaEnabled(false);
            admin.setStatus("active");
            admin.setCreatedAt(LocalDateTime.now());
            admin.setFailedLoginCount(0);
            User savedAdmin = userRepository.save(admin);
            
            UserRole adminRole = new UserRole();
            adminRole.setRoleId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            adminRole.setUserId(savedAdmin.getUserId());
            adminRole.setRole("ADMIN");
            adminRole.setCreatedAt(LocalDateTime.now());
            userRoleRepository.save(adminRole);
            
            UserRole userRole = new UserRole();
            userRole.setRoleId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            userRole.setUserId(savedAdmin.getUserId());
            userRole.setRole("USER");
            userRole.setCreatedAt(LocalDateTime.now());
            userRoleRepository.save(userRole);
            
            logger.info("========================================");
            logger.info("Default administrator account created:");
            logger.info("  Username: admin");
            logger.info("  Password: Admin@123");
            logger.info("  Email: admin@authcenter.com");
            logger.info("========================================");
        } else {
            logger.info("Default admin user already exists, skipping creation");
        }
    }
}