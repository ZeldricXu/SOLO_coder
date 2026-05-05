package com.ratelimiter.exception;

public class CircuitBreakerException extends RuntimeException {
    
    public CircuitBreakerException(String message) {
        super(message);
    }
}