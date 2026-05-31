package com.apishield.common.exception;

public class ValidationException extends ApiShieldException {

    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
