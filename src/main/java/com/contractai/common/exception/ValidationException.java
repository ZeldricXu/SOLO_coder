package com.contractai.common.exception;

import lombok.Getter;

@Getter
public class ValidationException extends BusinessException {

    public ValidationException(String message) {
        super(422, message);
    }
}
