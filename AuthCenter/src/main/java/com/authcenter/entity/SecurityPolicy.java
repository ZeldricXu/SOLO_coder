package com.authcenter.entity;

import javax.persistence.*;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "security_policies")
public class SecurityPolicy {
    
    @Id
    @Column(name = "policy_id", nullable = false, unique = true)
    private String policyId;
    
    @Column(name = "policy_type", nullable = false)
    private String policyType;
    
    @Column(name = "policy_name")
    private String policyName;
    
    @Column(name = "role_name")
    private String roleName;
    
    @Column(name = "max_failed_login")
    private Integer maxFailedLogin;
    
    @Column(name = "lock_duration")
    private Long lockDuration;
    
    @Column(name = "password_min_length")
    private Integer passwordMinLength;
    
    @Column(name = "password_require_complex")
    private Boolean passwordRequireComplex;
    
    @Column(name = "password_require_upper")
    private Boolean passwordRequireUpper;
    
    @Column(name = "password_require_lower")
    private Boolean passwordRequireLower;
    
    @Column(name = "password_require_digit")
    private Boolean passwordRequireDigit;
    
    @Column(name = "password_require_special")
    private Boolean passwordRequireSpecial;
    
    @Column(name = "session_max_duration")
    private Long sessionMaxDuration;
    
    @Column(name = "session_ip_check")
    private Boolean sessionIpCheck;
    
    @Column(name = "session_device_check")
    private Boolean sessionDeviceCheck;
    
    @Column(name = "session_device_id_check")
    private Boolean sessionDeviceIdCheck;
    
    @Column(name = "mfa_required")
    private Boolean mfaRequired;
    
    @Column(name = "mfa_trusted_device_duration")
    private Long mfaTrustedDeviceDuration;
    
    @Column(name = "token_expiration")
    private Long tokenExpiration;
    
    @Column(name = "refresh_token_expiration")
    private Long refreshTokenExpiration;
    
    @Column(name = "session_idle_timeout")
    private Long sessionIdleTimeout;
    
    @Column(name = "concurrent_sessions_allowed")
    private Integer concurrentSessionsAllowed;
    
    @Column(name = "password_history_count")
    private Integer passwordHistoryCount;
    
    @Column(name = "password_expiration_days")
    private Integer passwordExpirationDays;
    
    @Column(name = "account_inactivity_days")
    private Integer accountInactivityDays;
    
    @Column(name = "captcha_enabled")
    private Boolean captchaEnabled;
    
    @Column(name = "captcha_threshold")
    private Integer captchaThreshold;
    
    @Column(columnDefinition = "TEXT")
    private String config;
    
    @Column(nullable = false)
    private Integer priority = 0;
    
    @Column(nullable = false)
    private Boolean enabled = true;
    
    public SecurityPolicy() {
    }
    
    public String getPolicyId() {
        return policyId;
    }
    
    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }
    
    public String getPolicyType() {
        return policyType;
    }
    
    public void setPolicyType(String policyType) {
        this.policyType = policyType;
    }
    
    public String getPolicyName() {
        return policyName;
    }
    
    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }
    
    public String getRoleName() {
        return roleName;
    }
    
    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }
    
    public Integer getMaxFailedLogin() {
        return maxFailedLogin;
    }
    
    public void setMaxFailedLogin(Integer maxFailedLogin) {
        this.maxFailedLogin = maxFailedLogin;
    }
    
    public Long getLockDuration() {
        return lockDuration;
    }
    
    public void setLockDuration(Long lockDuration) {
        this.lockDuration = lockDuration;
    }
    
    public Integer getPasswordMinLength() {
        return passwordMinLength;
    }
    
    public void setPasswordMinLength(Integer passwordMinLength) {
        this.passwordMinLength = passwordMinLength;
    }
    
    public Boolean getPasswordRequireComplex() {
        return passwordRequireComplex;
    }
    
    public void setPasswordRequireComplex(Boolean passwordRequireComplex) {
        this.passwordRequireComplex = passwordRequireComplex;
    }
    
    public Boolean getPasswordRequireUpper() {
        return passwordRequireUpper;
    }
    
    public void setPasswordRequireUpper(Boolean passwordRequireUpper) {
        this.passwordRequireUpper = passwordRequireUpper;
    }
    
    public Boolean getPasswordRequireLower() {
        return passwordRequireLower;
    }
    
    public void setPasswordRequireLower(Boolean passwordRequireLower) {
        this.passwordRequireLower = passwordRequireLower;
    }
    
    public Boolean getPasswordRequireDigit() {
        return passwordRequireDigit;
    }
    
    public void setPasswordRequireDigit(Boolean passwordRequireDigit) {
        this.passwordRequireDigit = passwordRequireDigit;
    }
    
    public Boolean getPasswordRequireSpecial() {
        return passwordRequireSpecial;
    }
    
    public void setPasswordRequireSpecial(Boolean passwordRequireSpecial) {
        this.passwordRequireSpecial = passwordRequireSpecial;
    }
    
    public Long getSessionMaxDuration() {
        return sessionMaxDuration;
    }
    
    public void setSessionMaxDuration(Long sessionMaxDuration) {
        this.sessionMaxDuration = sessionMaxDuration;
    }
    
    public Boolean getSessionIpCheck() {
        return sessionIpCheck;
    }
    
    public void setSessionIpCheck(Boolean sessionIpCheck) {
        this.sessionIpCheck = sessionIpCheck;
    }
    
    public Boolean getSessionDeviceCheck() {
        return sessionDeviceCheck;
    }
    
    public void setSessionDeviceCheck(Boolean sessionDeviceCheck) {
        this.sessionDeviceCheck = sessionDeviceCheck;
    }
    
    public Boolean getSessionDeviceIdCheck() {
        return sessionDeviceIdCheck;
    }
    
    public void setSessionDeviceIdCheck(Boolean sessionDeviceIdCheck) {
        this.sessionDeviceIdCheck = sessionDeviceIdCheck;
    }
    
    public Boolean getMfaRequired() {
        return mfaRequired;
    }
    
    public void setMfaRequired(Boolean mfaRequired) {
        this.mfaRequired = mfaRequired;
    }
    
    public Long getMfaTrustedDeviceDuration() {
        return mfaTrustedDeviceDuration;
    }
    
    public void setMfaTrustedDeviceDuration(Long mfaTrustedDeviceDuration) {
        this.mfaTrustedDeviceDuration = mfaTrustedDeviceDuration;
    }
    
    public Long getTokenExpiration() {
        return tokenExpiration;
    }
    
    public void setTokenExpiration(Long tokenExpiration) {
        this.tokenExpiration = tokenExpiration;
    }
    
    public Long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }
    
    public void setRefreshTokenExpiration(Long refreshTokenExpiration) {
        this.refreshTokenExpiration = refreshTokenExpiration;
    }
    
    public Long getSessionIdleTimeout() {
        return sessionIdleTimeout;
    }
    
    public void setSessionIdleTimeout(Long sessionIdleTimeout) {
        this.sessionIdleTimeout = sessionIdleTimeout;
    }
    
    public Integer getConcurrentSessionsAllowed() {
        return concurrentSessionsAllowed;
    }
    
    public void setConcurrentSessionsAllowed(Integer concurrentSessionsAllowed) {
        this.concurrentSessionsAllowed = concurrentSessionsAllowed;
    }
    
    public Integer getPasswordHistoryCount() {
        return passwordHistoryCount;
    }
    
    public void setPasswordHistoryCount(Integer passwordHistoryCount) {
        this.passwordHistoryCount = passwordHistoryCount;
    }
    
    public Integer getPasswordExpirationDays() {
        return passwordExpirationDays;
    }
    
    public void setPasswordExpirationDays(Integer passwordExpirationDays) {
        this.passwordExpirationDays = passwordExpirationDays;
    }
    
    public Integer getAccountInactivityDays() {
        return accountInactivityDays;
    }
    
    public void setAccountInactivityDays(Integer accountInactivityDays) {
        this.accountInactivityDays = accountInactivityDays;
    }
    
    public Boolean getCaptchaEnabled() {
        return captchaEnabled;
    }
    
    public void setCaptchaEnabled(Boolean captchaEnabled) {
        this.captchaEnabled = captchaEnabled;
    }
    
    public Integer getCaptchaThreshold() {
        return captchaThreshold;
    }
    
    public void setCaptchaThreshold(Integer captchaThreshold) {
        this.captchaThreshold = captchaThreshold;
    }
    
    public String getConfig() {
        return config;
    }
    
    public void setConfig(String config) {
        this.config = config;
    }
    
    public Integer getPriority() {
        return priority;
    }
    
    public void setPriority(Integer priority) {
        this.priority = priority;
    }
    
    public Boolean getEnabled() {
        return enabled;
    }
    
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}