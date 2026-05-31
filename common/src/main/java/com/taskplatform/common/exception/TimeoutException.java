package com.taskplatform.common.exception;

public class TimeoutException extends BusinessException {

    public TimeoutException(String message) {
        super(504, "TIMEOUT", message);
    }

    public TimeoutException(String operation, long timeoutMs) {
        super(504, "TIMEOUT", "Operation '" + operation + "' timed out after " + timeoutMs + "ms");
    }
}
