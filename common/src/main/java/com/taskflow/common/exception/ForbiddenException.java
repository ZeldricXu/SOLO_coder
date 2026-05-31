package com.taskflow.common.exception;

public class ForbiddenException extends BusinessException {

    public ForbiddenException() {
        super(403, "权限不足");
    }

    public ForbiddenException(String message) {
        super(403, message);
    }
}
