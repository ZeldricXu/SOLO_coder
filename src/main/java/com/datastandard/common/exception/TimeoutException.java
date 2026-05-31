package com.datastandard.common.exception;

import lombok.Getter;

@Getter
public class TimeoutException extends RuntimeException {

    private final Integer code;
    private final String message;
    private final Long timeoutMs;

    public TimeoutException(String message) {
        super(message);
        this.code = 504;
        this.message = message;
        this.timeoutMs = null;
    }

    public TimeoutException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.timeoutMs = null;
    }

    public TimeoutException(String message, Long timeoutMs) {
        super(message);
        this.code = 504;
        this.message = message;
        this.timeoutMs = timeoutMs;
    }

    public TimeoutException(Integer code, String message, Long timeoutMs) {
        super(message);
        this.code = code;
        this.message = message;
        this.timeoutMs = timeoutMs;
    }
}
