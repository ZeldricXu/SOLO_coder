package com.parking.platform.common.exception;

public class TimeoutException extends BusinessException {

    public TimeoutException(String message) {
        super(504, message);
    }

    public TimeoutException(String message, Throwable cause) {
        super(504, message, cause);
    }
}
