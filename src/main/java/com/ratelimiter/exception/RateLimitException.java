package com.ratelimiter.exception;

public class RateLimitException extends RuntimeException {
    
    private int responseCode;
    
    public RateLimitException(String message) {
        super(message);
        this.responseCode = 429;
    }
    
    public RateLimitException(String message, int responseCode) {
        super(message);
        this.responseCode = responseCode;
    }
    
    public int getResponseCode() {
        return responseCode;
    }
}