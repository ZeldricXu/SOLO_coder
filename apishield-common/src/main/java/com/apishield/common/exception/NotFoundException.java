package com.apishield.common.exception;

public class NotFoundException extends ApiShieldException {

    public NotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}
