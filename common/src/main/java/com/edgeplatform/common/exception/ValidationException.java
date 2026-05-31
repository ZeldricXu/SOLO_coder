package com.edgeplatform.common.exception;

import java.io.Serial;
import java.util.Map;

public class ValidationException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Map<String, String> errors;

    public ValidationException(String message, Map<String, String> errors) {
        super(422, message);
        this.errors = errors;
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
