package com.tracetopology.common.exception;

import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {

    private final String code;
    private final String message;
    private final Object[] args;

    public BaseException(String code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.args = null;
    }

    public BaseException(String code, String message, Object... args) {
        super(message);
        this.code = code;
        this.message = message;
        this.args = args;
    }

    public BaseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
        this.args = null;
    }
}
