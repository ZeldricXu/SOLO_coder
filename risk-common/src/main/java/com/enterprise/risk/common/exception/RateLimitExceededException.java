package com.enterprise.risk.common.exception;

public class RateLimitExceededException extends RiskException {

    public RateLimitExceededException(String message) {
        super("RATE_LIMIT_EXCEEDED", message);
    }

    public RateLimitExceededException(String message, Object details) {
        super("RATE_LIMIT_EXCEEDED", message, details);
    }
}
