package com.parking.platform.gateway.dto;

import java.time.Instant;

public class LoginResponse {

    private String token;
    private String tokenType = "Bearer";
    private Long expiresIn;
    private Instant issuedAt;
    private String userId;

    public LoginResponse() {}

    public LoginResponse(String token, Long expiresIn, String userId) {
        this.token = token;
        this.expiresIn = expiresIn;
        this.issuedAt = Instant.now();
        this.userId = userId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public Long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
