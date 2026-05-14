package com.configcenter.common.exception;

public class ConfigValidationException extends BusinessException {

    public ConfigValidationException(String message) {
        super(400, message);
    }

    public ConfigValidationException(String message, Throwable cause) {
        super(400, message, cause);
    }
}
