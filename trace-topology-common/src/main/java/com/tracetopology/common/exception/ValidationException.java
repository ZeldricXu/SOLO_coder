package com.tracetopology.common.exception;

public class ValidationException extends BaseException {

    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }

    public ValidationException(String field, String message) {
        super("VALIDATION_ERROR", String.format("参数校验失败: %s - %s", field, message));
    }
}
