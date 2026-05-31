package com.taskplatform.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final String errorCode;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
        this.errorCode = "BUSINESS_ERROR";
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.errorCode = "BUSINESS_ERROR";
    }

    public BusinessException(int code, String errorCode, String message) {
        super(message);
        this.code = code;
        this.errorCode = errorCode;
    }

    public BusinessException(int code, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.errorCode = errorCode;
    }
}
