package com.scheduler.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final String resourceId;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.resourceId = null;
    }

    public BusinessException(int code, String message, String resourceId) {
        super(message);
        this.code = code;
        this.resourceId = resourceId;
    }

    public static BusinessException validationError(String message) {
        return new BusinessException(422, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException conflict(String resourceId) {
        return new BusinessException(409, "Concurrent conflict detected", resourceId);
    }

    public static BusinessException timeout(String message) {
        return new BusinessException(504, message);
    }

    public static BusinessException internalError(String message) {
        return new BusinessException(500, message);
    }
}
