package com.modelguard.exception;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(404, message);
    }

    public ResourceNotFoundException(String resourceType, String id) {
        super(404, resourceType + " not found: " + id);
    }
}
