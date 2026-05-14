package com.authcenter.exception;

public class AuthException extends RuntimeException {
    
    private int code;
    
    public AuthException(int code, String message) {
        super(message);
        this.code = code;
    }
    
    public AuthException(String message) {
        super(message);
        this.code = 401;
    }
    
    public int getCode() {
        return code;
    }
    
    public void setCode(int code) {
        this.code = code;
    }
}