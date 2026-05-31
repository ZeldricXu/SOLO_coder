package com.taskflow.common.exception;

import lombok.Getter;

import java.util.Map;

@Getter
public class ValidationException extends BusinessException {

    private final Map<String, String> errors;

    public ValidationException(String message) {
        super(422, message);
        this.errors = null;
    }

    public ValidationException(Map<String, String> errors) {
        super(422, "参数校验失败");
        this.errors = errors;
    }

    public ValidationException(String field, String message) {
        super(422, "参数校验失败");
        this.errors = Map.of(field, message);
    }
}
