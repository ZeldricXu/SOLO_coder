package com.authcenter.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "mfa_records")
public class MfaRecord {
    
    @Id
    @Column(name = "mfa_id", nullable = false, unique = true)
    private String mfaId;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "mfa_type", nullable = false)
    private String mfaType;
    
    @Column(name = "mfa_code", nullable = false)
    private String mfaCode;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    
    @Column(nullable = false)
    private Boolean verified = false;
    
    public MfaRecord() {
    }
    
    public String getMfaId() {
        return mfaId;
    }
    
    public void setMfaId(String mfaId) {
        this.mfaId = mfaId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getMfaType() {
        return mfaType;
    }
    
    public void setMfaType(String mfaType) {
        this.mfaType = mfaType;
    }
    
    public String getMfaCode() {
        return mfaCode;
    }
    
    public void setMfaCode(String mfaCode) {
        this.mfaCode = mfaCode;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
    
    public Boolean getVerified() {
        return verified;
    }
    
    public void setVerified(Boolean verified) {
        this.verified = verified;
    }
}