package com.apishield.common.exception;

public class BusinessException extends ApiShieldException {

    public BusinessException(String message) {
        super("BUSINESS_ERROR", message);
    }

    public BusinessException(String code, String message) {
        super(code, message);
    }
}
