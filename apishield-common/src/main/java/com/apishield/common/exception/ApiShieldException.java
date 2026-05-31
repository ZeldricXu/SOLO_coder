package com.apishield.common.exception;

import lombok.Getter;

@Getter
public class ApiShieldException extends RuntimeException {
    private final String code;
    private final String message;

    public ApiShieldException(String code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public ApiShieldException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
    }
}
