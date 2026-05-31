package com.monitoring.common.exception;

import lombok.Getter;

@Getter
public class MonitoringException extends RuntimeException {

    private final Integer code;

    public MonitoringException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public MonitoringException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
