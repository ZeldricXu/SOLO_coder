package com.edgescheduler.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final String message;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(String message) {
        this(500, message);
    }

    public static BusinessException of(int code, String message) {
        return new BusinessException(code, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }

    public static BusinessException validationError(String message) {
        return new BusinessException(422, message);
    }

    public static BusinessException timeout(String message) {
        return new BusinessException(504, message);
    }
}
