package com.authcenter.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
public class AuditLog {
    
    @Id
    @Column(name = "audit_id", nullable = false, unique = true)
    private String auditId;
    
    @Column(name = "user_id")
    private String userId;
    
    @Column(name = "audit_type", nullable = false)
    private String auditType;
    
    @Column(name = "audit_result", nullable = false)
    private String auditResult;
    
    @Column(name = "ip_address")
    private String ipAddress;
    
    @Column(name = "device_info")
    private String deviceInfo;
    
    @Column(name = "audit_time", nullable = false)
    private LocalDateTime auditTime;
    
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;
    
    public AuditLog() {
    }
    
    public String getAuditId() {
        return auditId;
    }
    
    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getAuditType() {
        return auditType;
    }
    
    public void setAuditType(String auditType) {
        this.auditType = auditType;
    }
    
    public String getAuditResult() {
        return auditResult;
    }
    
    public void setAuditResult(String auditResult) {
        this.auditResult = auditResult;
    }
    
    public String getIpAddress() {
        return ipAddress;
    }
    
    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
    
    public String getDeviceInfo() {
        return deviceInfo;
    }
    
    public void setDeviceInfo(String deviceInfo) {
        this.deviceInfo = deviceInfo;
    }
    
    public LocalDateTime getAuditTime() {
        return auditTime;
    }
    
    public void setAuditTime(LocalDateTime auditTime) {
        this.auditTime = auditTime;
    }
    
    public String getDetails() {
        return details;
    }
    
    public void setDetails(String details) {
        this.details = details;
    }
}