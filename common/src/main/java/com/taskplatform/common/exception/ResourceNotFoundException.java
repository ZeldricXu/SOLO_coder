package com.taskplatform.common.exception;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resourceType, String id) {
        super(404, "NOT_FOUND", resourceType + " not found with id: " + id);
    }

    public ResourceNotFoundException(String message) {
        super(404, "NOT_FOUND", message);
    }
}
