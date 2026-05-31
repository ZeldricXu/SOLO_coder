package com.nftindexer.exception;

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

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException validationError(String message) {
        return new BusinessException(422, message);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(401, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }

    public static BusinessException internalError(String message) {
        return new BusinessException(500, message);
    }

    public static BusinessException timeout(String message) {
        return new BusinessException(504, message);
    }
}
