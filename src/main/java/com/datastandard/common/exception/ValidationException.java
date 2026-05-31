package com.datastandard.common.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class ValidationException extends RuntimeException {

    private final Integer code;
    private final String message;
    private final List<String> errors;

    public ValidationException(String message) {
        super(message);
        this.code = 422;
        this.message = message;
        this.errors = null;
    }

    public ValidationException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.errors = null;
    }

    public ValidationException(String message, List<String> errors) {
        super(message);
        this.code = 422;
        this.message = message;
        this.errors = errors;
    }

    public ValidationException(Integer code, String message, List<String> errors) {
        super(message);
        this.code = code;
        this.message = message;
        this.errors = errors;
    }
}
