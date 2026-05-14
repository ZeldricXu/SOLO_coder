package com.authcenter.dto;

public class TokenVerifyResponse {
    
    private boolean valid;
    private String userId;
    private String username;
    private String sessionId;
    
    public TokenVerifyResponse() {
    }
    
    public TokenVerifyResponse(boolean valid, String userId, String username, String sessionId) {
        this.valid = valid;
        this.userId = userId;
        this.username = username;
        this.sessionId = sessionId;
    }
    
    public boolean isValid() {
        return valid;
    }
    
    public void setValid(boolean valid) {
        this.valid = valid;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}