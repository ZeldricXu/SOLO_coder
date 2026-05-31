package com.taskplatform.common.exception;

import lombok.Getter;
import java.util.Map;

@Getter
public class ValidationException extends RuntimeException {

    private final int code = 422;
    private final String errorCode = "VALIDATION_ERROR";
    private final Map<String, String> fieldErrors;

    public ValidationException(String message) {
        super(message);
        this.fieldErrors = null;
    }

    public ValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public ValidationException(Map<String, String> fieldErrors) {
        super("Validation failed");
        this.fieldErrors = fieldErrors;
    }
}
