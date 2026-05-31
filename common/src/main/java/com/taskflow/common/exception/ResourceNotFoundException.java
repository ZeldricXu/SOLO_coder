package com.taskflow.common.exception;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resourceType, String id) {
        super(404, String.format("%s not found: %s", resourceType, id));
    }

    public ResourceNotFoundException(String message) {
        super(404, message);
    }
}
