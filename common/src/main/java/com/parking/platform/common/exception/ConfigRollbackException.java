package com.parking.platform.common.exception;

public class ConfigRollbackException extends BusinessException {

    public ConfigRollbackException(String message) {
        super(400, message);
    }

    public ConfigRollbackException(String message, Throwable cause) {
        super(400, message, cause);
    }
}
