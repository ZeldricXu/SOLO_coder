package com.solocoder.dns.common.exception;

public class BusinessException extends RuntimeException {
    private final int code;
    private final String details;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.details = null;
    }

    public BusinessException(int code, String message, String details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public BusinessException(String message) {
        this(500, message);
    }

    public int getCode() { return code; }
    public String getDetails() { return details; }
}
