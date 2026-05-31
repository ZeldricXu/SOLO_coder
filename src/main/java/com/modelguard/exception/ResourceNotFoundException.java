package com.modelguard.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceName, String resourceId) {
        super(String.format("%s not found: %s", resourceName, resourceId));
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
