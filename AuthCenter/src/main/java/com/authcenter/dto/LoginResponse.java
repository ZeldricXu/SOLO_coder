package com.authcenter.dto;

public class LoginResponse {
    
    private String token;
    private String sessionId;
    private String userId;
    private String username;
    
    public LoginResponse() {
    }
    
    public LoginResponse(String token, String sessionId, String userId, String username) {
        this.token = token;
        this.sessionId = sessionId;
        this.userId = userId;
        this.username = username;
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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
}