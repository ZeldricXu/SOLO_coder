package com.solocoder.dns.common.exception;

public class ValidationException extends BusinessException {
    public ValidationException(String message) {
        super(422, "参数校验失败", message);
    }

    public ValidationException(String field, String message) {
        super(422, "参数校验失败", field + ": " + message);
    }
}
