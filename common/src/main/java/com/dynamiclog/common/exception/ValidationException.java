package com.dynamiclog.common.exception;

public class ValidationException extends BusinessException {
    public ValidationException(String message) {
        super(422, "VALIDATION_ERROR", message);
    }

    public ValidationException(String field, String message) {
        super(422, "VALIDATION_ERROR", field + ": " + message);
    }
}
