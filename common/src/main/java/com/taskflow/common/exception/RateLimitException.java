package com.taskflow.common.exception;

public class RateLimitException extends BusinessException {

    public RateLimitException() {
        super(429, "请求过于频繁，请稍后再试");
    }

    public RateLimitException(String message) {
        super(429, message);
    }
}
