package com.logistics.exception;

public class LogisticsException extends RuntimeException {

    private Integer code;

    public LogisticsException(String message) {
        super(message);
        this.code = 500;
    }

    public LogisticsException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
