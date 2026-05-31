package com.datapipeline.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final String errorDetail;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.errorDetail = message;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.errorDetail = message;
    }

    public static BusinessException validationError(String detail) {
        return new BusinessException(422, detail);
    }

    public static BusinessException notFound(String detail) {
        return new BusinessException(404, detail);
    }

    public static BusinessException timeout(String detail) {
        return new BusinessException(504, detail);
    }

    public static BusinessException internalError(String detail) {
        return new BusinessException(500, detail);
    }

}
