package com.parking.platform.common.exception;

public class RateLimitExceededException extends BusinessException {

    private final Long retryAfterSeconds;

    public RateLimitExceededException(String message) {
        super(429, message);
        this.retryAfterSeconds = 60L;
    }

    public RateLimitExceededException(String message, Long retryAfterSeconds) {
        super(429, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
