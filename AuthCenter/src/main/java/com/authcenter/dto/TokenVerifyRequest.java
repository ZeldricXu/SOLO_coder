package com.authcenter.dto;

import javax.validation.constraints.NotBlank;

public class TokenVerifyRequest {
    
    @NotBlank(message = "令牌不能为空")
    private String token;
    
    public TokenVerifyRequest() {
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
}