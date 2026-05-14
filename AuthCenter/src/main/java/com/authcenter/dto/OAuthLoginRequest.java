package com.authcenter.dto;

import javax.validation.constraints.NotBlank;

public class OAuthLoginRequest {
    
    @NotBlank(message = "OAuth提供商不能为空")
    private String provider;
    
    @NotBlank(message = "OAuth令牌不能为空")
    private String oauthToken;
    
    public OAuthLoginRequest() {
    }
    
    public String getProvider() {
        return provider;
    }
    
    public void setProvider(String provider) {
        this.provider = provider;
    }
    
    public String getOauthToken() {
        return oauthToken;
    }
    
    public void setOauthToken(String oauthToken) {
        this.oauthToken = oauthToken;
    }
}