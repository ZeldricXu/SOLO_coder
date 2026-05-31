package com.chaoslab.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;
    private final String traceId;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.traceId = null;
    }

    public BusinessException(Integer code, String message, String traceId) {
        super(message);
        this.code = code;
        this.traceId = traceId;
    }

    public BusinessException(String message) {
        this(500, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException validationError(String message) {
        return new BusinessException(422, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }

    public static BusinessException timeout(String message) {
        return new BusinessException(504, message);
    }
}
