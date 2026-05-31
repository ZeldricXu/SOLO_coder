package com.taskflow.common.exception;

public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(409, message);
    }

    public ConflictException(String resource, String id) {
        super(409, String.format("Conflict on %s: %s", resource, id));
    }
}
