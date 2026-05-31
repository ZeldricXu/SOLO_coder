package com.chain.infrastructure.common.exception;

import lombok.Getter;

@Getter
public class ValidationException extends RuntimeException {

    private final String details;

    public ValidationException(String message, String details) {
        super(message);
        this.details = details;
    }

    public ValidationException(String message) {
        this(message, null);
    }
}
