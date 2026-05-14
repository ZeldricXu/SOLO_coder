package com.authcenter.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "oauth_bindings")
public class OAuthBinding {
    
    @Id
    @Column(name = "oauth_id", nullable = false, unique = true)
    private String oauthId;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(nullable = false)
    private String provider;
    
    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;
    
    @Column(name = "bind_time", nullable = false)
    private LocalDateTime bindTime;
    
    @Column(nullable = false)
    private String status = "active";
    
    public OAuthBinding() {
    }
    
    public String getOauthId() {
        return oauthId;
    }
    
    public void setOauthId(String oauthId) {
        this.oauthId = oauthId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getProvider() {
        return provider;
    }
    
    public void setProvider(String provider) {
        this.provider = provider;
    }
    
    public String getProviderUserId() {
        return providerUserId;
    }
    
    public void setProviderUserId(String providerUserId) {
        this.providerUserId = providerUserId;
    }
    
    public LocalDateTime getBindTime() {
        return bindTime;
    }
    
    public void setBindTime(LocalDateTime bindTime) {
        this.bindTime = bindTime;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
}