package com.device.platform.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final String traceId;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.traceId = null;
    }

    public BusinessException(int code, String message, String traceId) {
        super(message);
        this.code = code;
        this.traceId = traceId;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.traceId = null;
    }
}
